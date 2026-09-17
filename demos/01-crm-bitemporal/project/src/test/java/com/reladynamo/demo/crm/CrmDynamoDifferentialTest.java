package com.reladynamo.demo.crm;

import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.MithraTransactionalObject;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.reladynamo.demo.crm.domain.CallFinder;
import com.reladynamo.demo.crm.domain.CallList;
import com.reladynamo.demo.crm.domain.ConsentRecord;
import com.reladynamo.demo.crm.domain.ConsentRecordFinder;
import com.reladynamo.demo.crm.domain.ConsentRecordList;
import com.reladynamo.demo.crm.domain.CustomerFinder;
import com.reladynamo.demo.crm.domain.CustomerList;
import com.reladynamo.demo.crm.domain.IndustryFinder;
import com.reladynamo.demo.crm.domain.IndustryList;
import com.reladynamo.demo.crm.query.CrmAsOfQueries;
import com.reladynamo.demo.crm.util.DemoTimestamps;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline claim, on the widest demo: the demo's own object-model XML is parsed
 * generically, H2 is seeded exactly as today, those rows are mirrored through
 * {@link DynamoDbWriter}, and the three temporal flavours round-trip identically.
 *
 * <p>Representatives, one of each flavour (not all ~46 entities):
 * <ul>
 *   <li>{@code Customer} — bitemporal</li>
 *   <li>{@code Call} — audit-only (processingDate)</li>
 *   <li>{@code Industry} — plain reference</li>
 * </ul>
 * {@code ConsentRecord} is also mirrored because the GDPR demonstration is the point of
 * this demo: one business date, two processing dates, two different lawful/unlawful answers.
 *
 * <p>Row sets are compared with {@link TemporalRowSetDiffer}. A real divergence is left
 * failing — it is not weakened to reach green.
 *
 * <p>This does not yet rebind Reladomo finders onto DynamoDB. GDPR as-of on the adapter
 * side is computed from decoded items with the same half-open rule Reladomo uses
 * ({@code from &lt;= asOf &lt; thru} on both axes). Finder materialisation is a separate
 * seam, already gated in the adapter module.
 */
class CrmDynamoDifferentialTest
{
    private static final ReladomoTestHarness HARNESS = new ReladomoTestHarness();

    private static LocalDynamoDb ddb;
    private static EntityMapping customerMapping;
    private static EntityMapping callMapping;
    private static EntityMapping industryMapping;
    private static EntityMapping consentMapping;
    private static Store customers;
    private static Store calls;
    private static Store industries;
    private static Store consents;

    private List<Map<String, Object>> h2Customers;
    private List<Map<String, Object>> h2Calls;
    private List<Map<String, Object>> h2Industries;
    private List<Map<String, Object>> h2Consents;
    private List<Map<String, Object>> ddbCustomers;
    private List<Map<String, Object>> ddbCalls;
    private List<Map<String, Object>> ddbIndustries;
    private List<Map<String, Object>> ddbConsents;

    @BeforeAll
    static void startStores()
    {
        HARNESS.setUp();
        ddb = LocalDynamoDb.start();

        // The mapping comes from the SAME XML the generated objects came from — that is what
        // makes this a test of the generic adapter rather than of a hand-written mapping.
        MithraObjectXmlParser parser = new MithraObjectXmlParser();
        customerMapping = parser.parse(loadDemoXml("Customer.xml"));
        callMapping = parser.parse(loadDemoXml("Call.xml"));
        industryMapping = parser.parse(loadDemoXml("Industry.xml"));
        consentMapping = parser.parse(loadDemoXml("ConsentRecord.xml"));

        TableCreator creator = new TableCreator(ddb.client());
        creator.create(customerMapping);
        creator.create(callMapping);
        creator.create(industryMapping);
        creator.create(consentMapping);

        customers = new Store(ddb, customerMapping);
        calls = new Store(ddb, callMapping);
        industries = new Store(ddb, industryMapping);
        consents = new Store(ddb, consentMapping);
    }

    @AfterAll
    static void stopStores()
    {
        try
        {
            if (ddb != null)
            {
                ddb.close();
            }
        }
        finally
        {
            HARNESS.tearDown();
        }
    }

    @BeforeEach
    void mirrorH2IntoDynamo()
    {
        h2Customers = extractDated(CustomerFinder.getFinderInstance(), allCustomerVersions());
        h2Calls = extractDated(CallFinder.getFinderInstance(), allCallVersions());
        h2Industries = extractNonDated(IndustryFinder.getFinderInstance(), allIndustries());
        h2Consents = extractDated(ConsentRecordFinder.getFinderInstance(), allConsentVersions());

        customers.purgeAll(customers.scanAll());
        calls.purgeAll(calls.scanAll());
        industries.purgeAll(industries.scanAll());
        consents.purgeAll(consents.scanAll());

        customers.push(h2Customers);
        calls.push(h2Calls);
        industries.push(h2Industries);
        consents.push(h2Consents);

        ddbCustomers = customers.scanAll();
        ddbCalls = calls.scanAll();
        ddbIndustries = industries.scanAll();
        ddbConsents = consents.scanAll();
    }

    @Test
    void demo_xml_parses_as_three_temporal_flavours()
    {
        assertThat(customerMapping.temporal().flavour())
                .as("Customer.xml must stay bitemporal; if the parser cannot consume it "
                        + "unchanged that is a conformance finding, not a reason to edit the XML")
                .isEqualTo(TemporalMapping.Flavour.BITEMPORAL);
        assertThat(customerMapping.temporal().hasBusinessDate()).isTrue();
        assertThat(customerMapping.temporal().hasProcessingDate()).isTrue();
        assertThat(CustomerFinder.getAsOfAttributes()).hasSize(2);

        assertThat(callMapping.temporal().flavour())
                .as("Call.xml must stay processing-date-only; if the parser cannot consume it "
                        + "unchanged that is a conformance finding, not a reason to edit the XML")
                .isEqualTo(TemporalMapping.Flavour.AUDIT_ONLY);
        assertThat(callMapping.temporal().hasProcessingDate()).isTrue();
        assertThat(callMapping.temporal().hasBusinessDate()).isFalse();
        assertThat(CallFinder.getAsOfAttributes()).hasSize(1);
        assertThat(CallFinder.getAsOfAttributes()[0].isProcessingDate()).isTrue();

        assertThat(industryMapping.temporal().flavour())
                .as("Industry.xml must stay plain (no AsOfAttribute); if the parser cannot consume it "
                        + "unchanged that is a conformance finding, not a reason to edit the XML")
                .isEqualTo(TemporalMapping.Flavour.NONE);
        assertThat(industryMapping.temporal().hasBusinessDate()).isFalse();
        assertThat(industryMapping.temporal().hasProcessingDate()).isFalse();
        assertThat(IndustryFinder.getAsOfAttributes()).isNull();

        assertThat(consentMapping.temporal().flavour())
                .as("ConsentRecord.xml must stay bitemporal — the GDPR demo depends on both axes")
                .isEqualTo(TemporalMapping.Flavour.BITEMPORAL);
    }

    @Test
    void customer_call_and_industry_row_sets_round_trip_identically()
    {
        assertIdentical("CUSTOMER", h2Customers, ddbCustomers);
        assertIdentical("CALL_RECORD", h2Calls, ddbCalls);
        assertIdentical("INDUSTRY", h2Industries, ddbIndustries);
        assertIdentical("CONSENT_RECORD", h2Consents, ddbConsents);
    }

    /**
     * GDPR: outreach email sent 2025-04-01 14:00. Consent withdrawn effective 2025-03-15,
     * recorded 2025-07-01. Same business date, two processing dates, two answers.
     *
     * <p>Lawful as known then; absent as known now. DynamoDB must reproduce both answers
     * identically — that is the demonstration this demo exists to make.
     */
    @Test
    void gdpr_consent_one_business_date_two_processing_dates_two_answers_from_dynamodb()
    {
        Timestamp business = DemoTimestamps.B_QUERY_APR;
        Timestamp knownThen = DemoTimestamps.P_KNOWN_APR;
        Timestamp knownNow = DemoTimestamps.P_TODAY;

        ConsentRecord h2Then = CrmAsOfQueries.acmeEmailConsent(business, knownThen);
        ConsentRecord h2Now = CrmAsOfQueries.acmeEmailConsent(business, knownNow);

        assertThat(h2Then).as("H2 as-known-then must have a consent row").isNotNull();
        assertThat(h2Now).as("H2 as-known-now must have a consent row").isNotNull();
        assertThat(h2Then.isGranted())
                .as("H2: outreach on 2025-04-01 was lawful as known on 2025-04-02")
                .isTrue();
        assertThat(h2Then.getLawfulBasis()).isEqualTo("consent");
        assertThat(h2Now.isGranted())
                .as("H2: the same business date is withdrawn as known today")
                .isFalse();
        assertThat(h2Now.getLawfulBasis()).isEqualTo("withdrawn");
        assertThat(h2Then.isGranted())
                .as("the two processing dates must disagree — otherwise the GDPR demo is vacuous")
                .isNotEqualTo(h2Now.isGranted());

        Map<String, Object> ddbThen = consentAt(ddbConsents, DemoIds.CONSENT_EMAIL, business, knownThen);
        Map<String, Object> ddbNow = consentAt(ddbConsents, DemoIds.CONSENT_EMAIL, business, knownNow);

        assertThat(ddbThen)
                .as("DynamoDB as-known-then must find a consent rectangle:%n%s",
                        describeRows(ddbConsents))
                .isNotNull();
        assertThat(ddbNow)
                .as("DynamoDB as-known-now must find a consent rectangle:%n%s",
                        describeRows(ddbConsents))
                .isNotNull();

        assertThat(booleanValue(ddbThen, "granted"))
                .as("DynamoDB as-known-then must equal H2: lawful/consent. DDB row:%n%s", ddbThen)
                .isEqualTo(h2Then.isGranted());
        assertThat(ddbThen.get("lawfulBasis")).isEqualTo(h2Then.getLawfulBasis());
        assertThat(booleanValue(ddbNow, "granted"))
                .as("DynamoDB as-known-now must equal H2: withdrawn. DDB row:%n%s", ddbNow)
                .isEqualTo(h2Now.isGranted());
        assertThat(ddbNow.get("lawfulBasis")).isEqualTo(h2Now.getLawfulBasis());

        assertThat(booleanValue(ddbThen, "granted"))
                .as("DynamoDB must reproduce two different answers, not collapse them")
                .isNotEqualTo(booleanValue(ddbNow, "granted"));
        assertThat(outreachLawful(ddbThen)).isEqualTo("YES");
        assertThat(outreachLawful(ddbNow)).isEqualTo("NO");
    }

    @Test
    void acme_customer_history_rectangles_match_from_dynamodb()
    {
        CustomerList history = CrmAsOfQueries.acmeHistoryRectangles();
        List<Map<String, Object>> h2Acme = extractDated(CustomerFinder.getFinderInstance(), history);
        List<Map<String, Object>> ddbAcme = rowsFor(ddbCustomers, "customerId", DemoIds.ACME);

        assertThat(h2Acme)
                .as("Acme must have several processing/business rectangles:%n%s", describeRows(h2Acme))
                .hasSizeGreaterThanOrEqualTo(3);

        assertIdentical("CUSTOMER Acme history", h2Acme, ddbAcme);

        boolean sawRename = false;
        boolean sawRevenue = false;
        for (int i = 0; i < ddbAcme.size(); i++)
        {
            Map<String, Object> row = ddbAcme.get(i);
            if ("Acme Corporation".equals(row.get("name")))
            {
                sawRename = true;
            }
            Object revenue = row.get("annualRevenue");
            if (revenue instanceof BigDecimal
                    && ((BigDecimal) revenue).compareTo(new BigDecimal("62000000.00")) == 0)
            {
                sawRevenue = true;
            }
        }
        assertThat(sawRename)
                .as("DynamoDB must keep the renamed Acme Corporation rectangle:%n%s",
                        describeRows(ddbAcme))
                .isTrue();
        assertThat(sawRevenue)
                .as("DynamoDB must keep the 62000000.00 revenue rectangle:%n%s",
                        describeRows(ddbAcme))
                .isTrue();
    }

    /**
     * Reladomo as-of: {@code from &lt;= asOf &lt; thru} on each declared axis
     * ({@code toIsInclusive="false"} in the demo XML).
     */
    static boolean visibleAt(Map<String, Object> row, Timestamp businessAsOf, Timestamp processingAsOf)
    {
        Timestamp businessFrom = (Timestamp) row.get("businessDateFrom");
        Timestamp businessTo = (Timestamp) row.get("businessDateTo");
        Timestamp processingFrom = (Timestamp) row.get("processingDateFrom");
        Timestamp processingTo = (Timestamp) row.get("processingDateTo");
        if (businessFrom == null || businessTo == null
                || processingFrom == null || processingTo == null)
        {
            return false;
        }
        return !businessFrom.after(businessAsOf)
                && businessAsOf.before(businessTo)
                && !processingFrom.after(processingAsOf)
                && processingAsOf.before(processingTo);
    }

    private static Map<String, Object> consentAt(List<Map<String, Object>> rows, long consentId,
                                                 Timestamp businessAsOf, Timestamp processingAsOf)
    {
        Map<String, Object> found = null;
        int matches = 0;
        for (int i = 0; i < rows.size(); i++)
        {
            Map<String, Object> row = rows.get(i);
            if (longValue(row, "consentId") != consentId)
            {
                continue;
            }
            if (!visibleAt(row, businessAsOf, processingAsOf))
            {
                continue;
            }
            matches++;
            found = row;
        }
        if (matches > 1)
        {
            throw new IllegalStateException(
                    "expected at most one consent rectangle for consentId=" + consentId
                            + " business=" + businessAsOf + " processing=" + processingAsOf
                            + " but matched " + matches + ":\n" + describeRows(rows));
        }
        return found;
    }

    private static String outreachLawful(Map<String, Object> row)
    {
        return booleanValue(row, "granted") ? "YES" : "NO";
    }

    private static boolean booleanValue(Map<String, Object> row, String name)
    {
        Object value = row.get(name);
        if (!(value instanceof Boolean))
        {
            throw new IllegalStateException(
                    "expected Boolean for '" + name + "' but was " + value);
        }
        return ((Boolean) value).booleanValue();
    }

    private static long longValue(Map<String, Object> row, String name)
    {
        Object value = row.get(name);
        if (!(value instanceof Number))
        {
            throw new IllegalStateException(
                    "expected Number for '" + name + "' but was " + value);
        }
        return ((Number) value).longValue();
    }

    private static List<Map<String, Object>> rowsFor(List<Map<String, Object>> rows, String idAttr, long id)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows.size(); i++)
        {
            Map<String, Object> row = rows.get(i);
            Object value = row.get(idAttr);
            if (value instanceof Number && ((Number) value).longValue() == id)
            {
                out.add(row);
            }
        }
        return out;
    }

    private static void assertIdentical(String table,
                                        List<Map<String, Object>> reference,
                                        List<Map<String, Object>> adapter)
    {
        assertThat(reference)
                .as("%s: the H2 reference must produce rows, otherwise the comparison is vacuous", table)
                .isNotEmpty();
        RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
        assertThat(diff.isIdentical())
                .as("H2 and DynamoDB disagree for %s:%n%s%nH2:%n%s%nDDB:%n%s",
                        table, diff.describe(), describeRows(reference), describeRows(adapter))
                .isTrue();
    }

    private static String describeRows(List<Map<String, Object>> rows)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" row(s)\n");
        for (int i = 0; i < rows.size(); i++)
        {
            sb.append("  [").append(i).append("] ").append(rows.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static CustomerList allCustomerVersions()
    {
        CustomerList list = CustomerFinder.findMany(
                CustomerFinder.businessDate().equalsEdgePoint()
                        .and(CustomerFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        return list;
    }

    private static CallList allCallVersions()
    {
        CallList list = CallFinder.findMany(CallFinder.processingDate().equalsEdgePoint());
        list.setBypassCache(true);
        return list;
    }

    private static IndustryList allIndustries()
    {
        IndustryList list = IndustryFinder.findMany(IndustryFinder.all());
        list.setBypassCache(true);
        return list;
    }

    private static ConsentRecordList allConsentVersions()
    {
        ConsentRecordList list = ConsentRecordFinder.findMany(
                ConsentRecordFinder.businessDate().equalsEdgePoint()
                        .and(ConsentRecordFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        return list;
    }

    private static List<Map<String, Object>> extractDated(RelatedFinder finder, MithraList list)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++)
        {
            MithraDatedTransactionalObject item = (MithraDatedTransactionalObject) list.get(i);
            rows.add(MithraDataAccessor.extract(finder, item.zGetCurrentData()));
        }
        return rows;
    }

    private static List<Map<String, Object>> extractNonDated(RelatedFinder finder, MithraList list)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++)
        {
            MithraTransactionalObject item = (MithraTransactionalObject) list.get(i);
            rows.add(MithraDataAccessor.extract(finder, item.zGetCurrentData()));
        }
        return rows;
    }

    static String loadDemoXml(String fileName)
    {
        String path = "/reladomo/models/" + fileName;
        InputStream in = CrmDynamoDifferentialTest.class.getResourceAsStream(path);
        if (in == null)
        {
            throw new IllegalStateException("missing classpath resource " + path
                    + " — parse the demo's own XML, do not substitute a hand-written mapping");
        }
        StringBuilder xml = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                xml.append(line).append('\n');
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("could not read " + path, e);
        }
        return xml.toString();
    }

    /** One mapped DynamoDB table plus the codec/writer used to push H2 snapshots through the adapter. */
    static final class Store
    {
        private final LocalDynamoDb ddb;
        private final EntityMapping mapping;
        private final ItemCodec codec;
        private final DynamoDbWriter writer;

        Store(LocalDynamoDb ddb, EntityMapping mapping)
        {
            this.ddb = ddb;
            this.mapping = mapping;
            this.codec = new ItemCodec(mapping);
            this.writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        }

        void push(List<Map<String, Object>> rows)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                // Snapshot replay, not an ORM insert: the same H2 version may already occupy
                // this pk+sk from an earlier assertion or a bound-portal write.
                writer.upsert(rows.get(i));
            }
        }

        void purgeAll(List<Map<String, Object>> rows)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                writer.purge(rows.get(i));
            }
        }

        List<Map<String, Object>> scanAll()
        {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            Map<String, AttributeValue> start = null;
            do
            {
                final Map<String, AttributeValue> exclusiveStart = start;
                ScanResponse response = ddb.client().scan(b ->
                {
                    b.tableName(mapping.tableName()).consistentRead(true);
                    if (exclusiveStart != null && !exclusiveStart.isEmpty())
                    {
                        b.exclusiveStartKey(exclusiveStart);
                    }
                });
                List<Map<String, AttributeValue>> items = response.items();
                for (int i = 0; i < items.size(); i++)
                {
                    rows.add(codec.decode(items.get(i)));
                }
                start = response.lastEvaluatedKey();
            }
            while (start != null && !start.isEmpty());
            return rows;
        }
    }
}
