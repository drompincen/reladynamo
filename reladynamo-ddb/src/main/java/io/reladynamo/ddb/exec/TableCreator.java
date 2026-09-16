package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.mapping.MappingValidator;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PhysicalDesign;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateGlobalSecondaryIndexAction;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexUpdate;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates or reconciles a DynamoDB table for an {@link EntityMapping}.
 *
 * <p>Every table has both {@code pk} (S) and {@code sk} (S), including non-dated entities. Those
 * use the constant sort key {@code v1#ND} so no caller has to special-case a missing sort key.
 *
 * <p>Default billing is {@code PAY_PER_REQUEST}. Requested-versus-actual design is a checked
 * invariant, not an assumption that {@code ACTIVE} means ready:
 * {@link SchemaReconcileOutcome#CREATE} / {@link SchemaReconcileOutcome#VALIDATE} /
 * {@link SchemaReconcileOutcome#ADD_INDEX} / {@link SchemaReconcileOutcome#BACKFILL_INDEX_KEY} /
 * {@link SchemaReconcileOutcome#INCOMPATIBLE}. {@link #create} throws
 * {@link SchemaReconcileException} on the two refuse outcomes. Returns only after the table
 * (and every requested GSI that was created or already present) is {@code ACTIVE}, or throws
 * {@link TableCreateTimeoutException}.
 */
public final class TableCreator {

    public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

    private final DynamoDbClient client;
    private final Options options;

    public TableCreator(DynamoDbClient client) {
        this(client, Options.defaults());
    }

    public TableCreator(DynamoDbClient client, Options options) {
        this.client = Objects.requireNonNull(client, "client");
        this.options = Objects.requireNonNull(options, "options");
    }

    public void create(EntityMapping mapping) {
        create(mapping, Collections.emptyList());
    }

    public void create(PhysicalDesign design) {
        Objects.requireNonNull(design, "design");
        create(design.entity(), design.gsis());
    }

    public SchemaReconcileResult apply(EntityMapping mapping) {
        return apply(mapping, Collections.emptyList());
    }

    public SchemaReconcileResult apply(PhysicalDesign design) {
        Objects.requireNonNull(design, "design");
        return apply(design.entity(), design.gsis());
    }

    public void create(EntityMapping mapping, List<GsiSpec> gsis) {
        SchemaReconcileResult result = apply(mapping, gsis);
        if (result.isRefused()) {
            throw new SchemaReconcileException(result);
        }
    }

    public SchemaReconcileResult apply(EntityMapping mapping, List<GsiSpec> gsis) {
        Objects.requireNonNull(mapping, "mapping");
        List<GsiSpec> indexes = gsis == null ? Collections.emptyList() : gsis;
        refuseUnsupportedProjections(mapping, indexes);
        String tableName = mapping.tableName();
        TableDescription existing = describe(tableName);
        if (existing == null) {
            try {
                client.createTable(createRequest(tableName, indexes));
            } catch (ResourceInUseException raced) {
                waitUntilTableActive(tableName);
                return reconcileExisting(tableName, indexes);
            }
            waitUntilRequestedReady(tableName, indexes);
            return SchemaReconcileResult.of(
                    SchemaReconcileOutcome.CREATE,
                    tableName,
                    "created table with HASH=" + options.pkAttributeName()
                            + " RANGE=" + options.skAttributeName());
        }
        waitUntilTableActive(tableName);
        return reconcileExisting(tableName, indexes);
    }

    private SchemaReconcileResult reconcileExisting(String tableName, List<GsiSpec> indexes) {
        TableDescription table = describe(tableName);
        if (table == null) {
            throw new TableCreateTimeoutException(
                    "table '" + tableName + "' disappeared during reconciliation");
        }
        if (!keySchemaMatches(table)) {
            String detail = "key schema is incompatible: requested HASH="
                    + options.pkAttributeName() + " RANGE=" + options.skAttributeName()
                    + ", actual " + formatKeySchema(table.keySchema());
            return SchemaReconcileResult.of(SchemaReconcileOutcome.INCOMPATIBLE, tableName, detail);
        }
        if (!keyAttributeTypesAreString(table)) {
            return SchemaReconcileResult.of(
                    SchemaReconcileOutcome.INCOMPATIBLE,
                    tableName,
                    "key schema is incompatible: requested pk/sk attribute types S, actual "
                            + formatAttributeTypes(table));
        }
        List<String> missing = new ArrayList<String>();
        for (int i = 0; i < indexes.size(); i++) {
            GsiSpec gsi = indexes.get(i);
            GlobalSecondaryIndexDescription actual = findGsi(table, gsi.name());
            if (actual == null) {
                missing.add(gsi.name());
                continue;
            }
            if (!gsiKeySchemaMatches(gsi, actual)) {
                String detail = "GSI '" + gsi.name() + "' key schema is incompatible: requested "
                        + formatRequestedGsiKeys(gsi) + ", actual " + formatKeySchema(actual.keySchema());
                return SchemaReconcileResult.of(SchemaReconcileOutcome.INCOMPATIBLE, tableName, detail);
            }
        }
        if (missing.isEmpty()) {
            waitUntilRequestedReady(tableName, indexes);
            return SchemaReconcileResult.of(
                    SchemaReconcileOutcome.VALIDATE,
                    tableName,
                    "existing table matches requested key schema and indexes");
        }
        List<String> lacking = gsiKeyAttributesOnMissingIndexes(indexes, missing);
        if (anyItemLacksAttributes(tableName, lacking)) {
            String detail = "GSI " + missing
                    + " missing and existing rows lack key attributes " + lacking
                    + "; stamp index keys then retry (BACKFILL_INDEX_KEY)";
            return new SchemaReconcileResult(
                    SchemaReconcileOutcome.BACKFILL_INDEX_KEY, tableName, detail, missing);
        }
        for (int i = 0; i < indexes.size(); i++) {
            GsiSpec gsi = indexes.get(i);
            if (missing.contains(gsi.name())) {
                addGsi(tableName, gsi);
            }
        }
        waitUntilRequestedReady(tableName, indexes);
        return new SchemaReconcileResult(
                SchemaReconcileOutcome.ADD_INDEX,
                tableName,
                "added missing GSI(s) " + missing,
                missing);
    }

    private CreateTableRequest createRequest(String tableName, List<GsiSpec> gsis) {
        String pk = options.pkAttributeName();
        String sk = options.skAttributeName();
        Map<String, AttributeDefinition> defs = new LinkedHashMap<String, AttributeDefinition>();
        defs.put(pk, stringAttr(pk));
        defs.put(sk, stringAttr(sk));

        List<GlobalSecondaryIndex> indexRequests = new ArrayList<GlobalSecondaryIndex>();
        for (int i = 0; i < gsis.size(); i++) {
            GsiSpec gsi = gsis.get(i);
            String gsiPk = gsi.partitionKeyAttributeName();
            defs.put(gsiPk, stringAttr(gsiPk));
            List<KeySchemaElement> keySchema = gsiKeySchema(gsi);
            String gsiSk = gsi.sortKeyAttributeName();
            if (gsiSk != null) {
                defs.put(gsiSk, stringAttr(gsiSk));
            }
            GlobalSecondaryIndex.Builder gsiBuilder = GlobalSecondaryIndex.builder()
                    .indexName(gsi.name())
                    .keySchema(keySchema)
                    .projection(toProjection(gsi));
            if (options.billingMode() == BillingMode.PROVISIONED) {
                gsiBuilder.provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(options.readCapacityUnits())
                        .writeCapacityUnits(options.writeCapacityUnits())
                        .build());
            }
            indexRequests.add(gsiBuilder.build());
        }

        CreateTableRequest.Builder b = CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(new ArrayList<AttributeDefinition>(defs.values()))
                .keySchema(
                        KeySchemaElement.builder().attributeName(pk).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(sk).keyType(KeyType.RANGE).build())
                .billingMode(options.billingMode());
        if (options.billingMode() == BillingMode.PROVISIONED) {
            b.provisionedThroughput(ProvisionedThroughput.builder()
                    .readCapacityUnits(options.readCapacityUnits())
                    .writeCapacityUnits(options.writeCapacityUnits())
                    .build());
        }
        if (!indexRequests.isEmpty()) {
            b.globalSecondaryIndexes(indexRequests);
        }
        return b.build();
    }

    private void addGsi(String tableName, GsiSpec gsi) {
        Map<String, AttributeDefinition> defs = new LinkedHashMap<String, AttributeDefinition>();
        String gsiPk = gsi.partitionKeyAttributeName();
        defs.put(gsiPk, stringAttr(gsiPk));
        String gsiSk = gsi.sortKeyAttributeName();
        if (gsiSk != null) {
            defs.put(gsiSk, stringAttr(gsiSk));
        }
        CreateGlobalSecondaryIndexAction.Builder create = CreateGlobalSecondaryIndexAction.builder()
                .indexName(gsi.name())
                .keySchema(gsiKeySchema(gsi))
                .projection(toProjection(gsi));
        if (options.billingMode() == BillingMode.PROVISIONED) {
            create.provisionedThroughput(ProvisionedThroughput.builder()
                    .readCapacityUnits(options.readCapacityUnits())
                    .writeCapacityUnits(options.writeCapacityUnits())
                    .build());
        }
        client.updateTable(UpdateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(new ArrayList<AttributeDefinition>(defs.values()))
                .globalSecondaryIndexUpdates(GlobalSecondaryIndexUpdate.builder()
                        .create(create.build())
                        .build())
                .build());
    }

    private List<KeySchemaElement> gsiKeySchema(GsiSpec gsi) {
        List<KeySchemaElement> keySchema = new ArrayList<KeySchemaElement>();
        keySchema.add(KeySchemaElement.builder()
                .attributeName(gsi.partitionKeyAttributeName())
                .keyType(KeyType.HASH)
                .build());
        String gsiSk = gsi.sortKeyAttributeName();
        if (gsiSk != null) {
            keySchema.add(KeySchemaElement.builder()
                    .attributeName(gsiSk)
                    .keyType(KeyType.RANGE)
                    .build());
        }
        return keySchema;
    }

    private static AttributeDefinition stringAttr(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }

    private static void refuseUnsupportedProjections(EntityMapping mapping, List<GsiSpec> indexes) {
        for (int i = 0; i < indexes.size(); i++) {
            GsiSpec gsi = indexes.get(i);
            if (gsi.projection() != GsiSpec.Projection.ALL) {
                throw MappingValidator.unsupportedGsiProjection(
                        mapping.className(), gsi.name(), gsi.projection().name());
            }
        }
    }

    private static Projection toProjection(GsiSpec gsi) {
        if (gsi.projection() == GsiSpec.Projection.KEYS_ONLY) {
            return Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build();
        }
        if (gsi.projection() == GsiSpec.Projection.INCLUDE) {
            return Projection.builder()
                    .projectionType(ProjectionType.INCLUDE)
                    .nonKeyAttributes(gsi.projectedJavaNames())
                    .build();
        }
        return Projection.builder().projectionType(ProjectionType.ALL).build();
    }

    private TableDescription describe(String tableName) {
        try {
            DescribeTableResponse described = client.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            return described.table();
        } catch (ResourceNotFoundException missing) {
            return null;
        }
    }

    private TableStatus currentStatus(String tableName) {
        TableDescription table = describe(tableName);
        return table == null ? null : table.tableStatus();
    }

    private boolean keySchemaMatches(TableDescription table) {
        String hash = null;
        String range = null;
        List<KeySchemaElement> actual = table.keySchema();
        if (actual == null) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            KeySchemaElement element = actual.get(i);
            if (element.keyType() == KeyType.HASH) {
                hash = element.attributeName();
            } else if (element.keyType() == KeyType.RANGE) {
                range = element.attributeName();
            }
        }
        return options.pkAttributeName().equals(hash) && options.skAttributeName().equals(range);
    }

    private boolean keyAttributeTypesAreString(TableDescription table) {
        return isStringAttribute(table, options.pkAttributeName())
                && isStringAttribute(table, options.skAttributeName());
    }

    private static boolean isStringAttribute(TableDescription table, String name) {
        List<AttributeDefinition> defs = table.attributeDefinitions();
        if (defs == null) {
            return false;
        }
        for (int i = 0; i < defs.size(); i++) {
            AttributeDefinition def = defs.get(i);
            if (name.equals(def.attributeName())) {
                return def.attributeType() == ScalarAttributeType.S;
            }
        }
        return false;
    }

    private static GlobalSecondaryIndexDescription findGsi(TableDescription table, String name) {
        List<GlobalSecondaryIndexDescription> indexes = table.globalSecondaryIndexes();
        if (indexes == null) {
            return null;
        }
        for (int i = 0; i < indexes.size(); i++) {
            if (name.equals(indexes.get(i).indexName())) {
                return indexes.get(i);
            }
        }
        return null;
    }

    private static boolean gsiKeySchemaMatches(GsiSpec requested, GlobalSecondaryIndexDescription actual) {
        String hash = null;
        String range = null;
        List<KeySchemaElement> keys = actual.keySchema();
        if (keys == null) {
            return false;
        }
        for (int i = 0; i < keys.size(); i++) {
            KeySchemaElement element = keys.get(i);
            if (element.keyType() == KeyType.HASH) {
                hash = element.attributeName();
            } else if (element.keyType() == KeyType.RANGE) {
                range = element.attributeName();
            }
        }
        if (!requested.partitionKeyAttributeName().equals(hash)) {
            return false;
        }
        String wantedSk = requested.sortKeyAttributeName();
        if (wantedSk == null) {
            return range == null;
        }
        return wantedSk.equals(range);
    }

    private static String formatKeySchema(List<KeySchemaElement> keys) {
        if (keys == null || keys.isEmpty()) {
            return "HASH=<none> RANGE=<none>";
        }
        String hash = "<none>";
        String range = "<none>";
        for (int i = 0; i < keys.size(); i++) {
            KeySchemaElement element = keys.get(i);
            if (element.keyType() == KeyType.HASH) {
                hash = element.attributeName();
            } else if (element.keyType() == KeyType.RANGE) {
                range = element.attributeName();
            }
        }
        return "HASH=" + hash + " RANGE=" + range;
    }

    private static String formatRequestedGsiKeys(GsiSpec gsi) {
        String range = gsi.sortKeyAttributeName() == null ? "<none>" : gsi.sortKeyAttributeName();
        return "HASH=" + gsi.partitionKeyAttributeName() + " RANGE=" + range;
    }

    private static String formatAttributeTypes(TableDescription table) {
        StringBuilder sb = new StringBuilder();
        List<AttributeDefinition> defs = table.attributeDefinitions();
        if (defs == null) {
            return "<none>";
        }
        for (int i = 0; i < defs.size(); i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(defs.get(i).attributeName()).append('=').append(defs.get(i).attributeType());
        }
        return sb.toString();
    }

    private List<String> gsiKeyAttributesOnMissingIndexes(List<GsiSpec> indexes, List<String> missing) {
        List<String> attrs = new ArrayList<String>();
        for (int i = 0; i < indexes.size(); i++) {
            GsiSpec gsi = indexes.get(i);
            if (!missing.contains(gsi.name())) {
                continue;
            }
            addIfSparseKey(attrs, gsi.partitionKeyAttributeName());
            addIfSparseKey(attrs, gsi.sortKeyAttributeName());
        }
        return attrs;
    }

    private void addIfSparseKey(List<String> attrs, String attributeName) {
        if (attributeName == null) {
            return;
        }
        if (options.pkAttributeName().equals(attributeName)
                || options.skAttributeName().equals(attributeName)) {
            return;
        }
        if (!attrs.contains(attributeName)) {
            attrs.add(attributeName);
        }
    }

    private boolean anyItemLacksAttributes(String tableName, List<String> attributeNames) {
        if (attributeNames == null || attributeNames.isEmpty()) {
            return false;
        }
        for (int i = 0; i < attributeNames.size(); i++) {
            if (anyItemLacksAttribute(tableName, attributeNames.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean anyItemLacksAttribute(String tableName, String attributeName) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("#pk", options.pkAttributeName());
        names.put("#a", attributeName);
        Map<String, AttributeValue> exclusiveStartKey = null;
        while (true) {
            ScanRequest.Builder scan = ScanRequest.builder()
                    .tableName(tableName)
                    .projectionExpression("#pk, #a")
                    .expressionAttributeNames(names)
                    .limit(25);
            if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
                scan.exclusiveStartKey(exclusiveStartKey);
            }
            ScanResponse response = client.scan(scan.build());
            List<Map<String, AttributeValue>> items = response.items();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    AttributeValue value = items.get(i).get(attributeName);
                    if (value == null || Boolean.TRUE.equals(value.nul())) {
                        return true;
                    }
                }
            }
            exclusiveStartKey = response.lastEvaluatedKey();
            if (exclusiveStartKey == null || exclusiveStartKey.isEmpty()) {
                return false;
            }
        }
    }

    private void waitUntilTableActive(String tableName) {
        waitUntil(tableName, false, Collections.emptyList());
    }

    private void waitUntilRequestedReady(String tableName, List<GsiSpec> requested) {
        waitUntil(tableName, true, requested);
    }

    private void waitUntil(String tableName, boolean requireRequestedIndexes, List<GsiSpec> requested) {
        long deadlineNanos = System.nanoTime() + options.waitTimeout().toNanos();
        TableStatus last = null;
        while (true) {
            TableDescription table = describe(tableName);
            last = table == null ? null : table.tableStatus();
            if (table != null && table.tableStatus() == TableStatus.ACTIVE
                    && existingIndexesActive(table)
                    && (!requireRequestedIndexes || requestedIndexesReady(table, requested))) {
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new TableCreateTimeoutException(
                        "timed out after " + options.waitTimeout().toMillis()
                                + " ms waiting for table '" + tableName
                                + "' (and its indexes) to become ACTIVE (last status: "
                                + (last == null ? "MISSING" : last) + ")");
            }
            try {
                Thread.sleep(options.pollInterval().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TableCreateTimeoutException(
                        "interrupted while waiting for table '" + tableName + "' to become ACTIVE",
                        interrupted);
            }
        }
    }

    private static boolean existingIndexesActive(TableDescription table) {
        List<GlobalSecondaryIndexDescription> indexes = table.globalSecondaryIndexes();
        if (indexes == null || indexes.isEmpty()) {
            return true;
        }
        for (int i = 0; i < indexes.size(); i++) {
            if (indexes.get(i).indexStatus() != IndexStatus.ACTIVE) {
                return false;
            }
        }
        return true;
    }

    private static boolean requestedIndexesReady(TableDescription table, List<GsiSpec> requested) {
        if (requested == null || requested.isEmpty()) {
            return true;
        }
        for (int i = 0; i < requested.size(); i++) {
            GlobalSecondaryIndexDescription actual = findGsi(table, requested.get(i).name());
            if (actual == null || actual.indexStatus() != IndexStatus.ACTIVE) {
                return false;
            }
        }
        return true;
    }

    public static final class Options {
        private final BillingMode billingMode;
        private final Long readCapacityUnits;
        private final Long writeCapacityUnits;
        private final Duration waitTimeout;
        private final Duration pollInterval;
        private final String pkAttributeName;
        private final String skAttributeName;

        private Options(Builder builder) {
            this.billingMode = builder.billingMode;
            this.readCapacityUnits = builder.readCapacityUnits;
            this.writeCapacityUnits = builder.writeCapacityUnits;
            this.waitTimeout = builder.waitTimeout;
            this.pollInterval = builder.pollInterval;
            this.pkAttributeName = builder.pkAttributeName;
            this.skAttributeName = builder.skAttributeName;
        }

        public static Options defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public BillingMode billingMode() {
            return billingMode;
        }

        public Long readCapacityUnits() {
            return readCapacityUnits;
        }

        public Long writeCapacityUnits() {
            return writeCapacityUnits;
        }

        public Duration waitTimeout() {
            return waitTimeout;
        }

        public Duration pollInterval() {
            return pollInterval;
        }

        public String pkAttributeName() {
            return pkAttributeName;
        }

        public String skAttributeName() {
            return skAttributeName;
        }

        public static final class Builder {
            private BillingMode billingMode = BillingMode.PAY_PER_REQUEST;
            private Long readCapacityUnits;
            private Long writeCapacityUnits;
            private Duration waitTimeout = DEFAULT_WAIT_TIMEOUT;
            private Duration pollInterval = DEFAULT_POLL_INTERVAL;
            private String pkAttributeName = PhysicalDesign.PK_ATTR;
            private String skAttributeName = PhysicalDesign.SK_ATTR;

            public Builder billingMode(BillingMode v) {
                this.billingMode = Objects.requireNonNull(v, "billingMode");
                return this;
            }

            public Builder readCapacityUnits(Long v) {
                this.readCapacityUnits = v;
                return this;
            }

            public Builder writeCapacityUnits(Long v) {
                this.writeCapacityUnits = v;
                return this;
            }

            public Builder waitTimeout(Duration v) {
                this.waitTimeout = Objects.requireNonNull(v, "waitTimeout");
                return this;
            }

            public Builder pollInterval(Duration v) {
                this.pollInterval = Objects.requireNonNull(v, "pollInterval");
                return this;
            }

            public Builder pkAttributeName(String v) {
                this.pkAttributeName = Objects.requireNonNull(v, "pkAttributeName");
                return this;
            }

            public Builder skAttributeName(String v) {
                this.skAttributeName = Objects.requireNonNull(v, "skAttributeName");
                return this;
            }

            public Options build() {
                if (billingMode == BillingMode.PROVISIONED) {
                    if (readCapacityUnits == null || writeCapacityUnits == null
                            || readCapacityUnits.longValue() < 1L || writeCapacityUnits.longValue() < 1L) {
                        throw new IllegalArgumentException(
                                "PROVISIONED billing requires readCapacityUnits and writeCapacityUnits >= 1");
                    }
                }
                if (waitTimeout.isNegative() || waitTimeout.isZero()) {
                    throw new IllegalArgumentException("waitTimeout must be positive");
                }
                if (pollInterval.isNegative() || pollInterval.isZero()) {
                    throw new IllegalArgumentException("pollInterval must be positive");
                }
                return new Options(this);
            }
        }
    }
}
