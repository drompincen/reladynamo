package io.reladynamo.core.plan;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.mapping.TemporalAttributeNames;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Physical DynamoDB shape the planner needs: table, keys, temporal payload names, optional GSIs.
 * Built from {@link EntityMapping} plus planner-specific axis metadata the mapping layer owns.
 */
public final class PhysicalDesign {

    public static final String PRIMARY_INDEX = "PRIMARY";
    public static final String PK_ATTR = "pk";
    public static final String SK_ATTR = "sk";
    public static final String NON_DATED_SK = "v1#ND";

    private final EntityMapping entity;
    private final String pkAttributeName;
    private final String skAttributeName;
    private final String businessFromItemName;
    private final String businessThruItemName;
    private final String processingFromItemName;
    private final String processingThruItemName;
    private final String businessAsOfJavaName;
    private final String processingAsOfJavaName;
    private final String businessFromJavaName;
    private final String processingFromJavaName;
    private final boolean businessToInclusive;
    private final boolean processingToInclusive;
    private final Timestamp infinity;
    private final List<GsiSpec> gsis;
    private final int avgItemBytes;

    private PhysicalDesign(Builder builder) {
        this.entity = Objects.requireNonNull(builder.entity, "entity");
        this.pkAttributeName = builder.pkAttributeName;
        this.skAttributeName = builder.skAttributeName;
        this.businessFromItemName = builder.businessFromItemName;
        this.businessThruItemName = builder.businessThruItemName;
        this.processingFromItemName = builder.processingFromItemName;
        this.processingThruItemName = builder.processingThruItemName;
        this.businessAsOfJavaName = builder.businessAsOfJavaName;
        this.processingAsOfJavaName = builder.processingAsOfJavaName;
        this.businessFromJavaName = builder.businessFromJavaName;
        this.processingFromJavaName = builder.processingFromJavaName;
        this.businessToInclusive = builder.businessToInclusive;
        this.processingToInclusive = builder.processingToInclusive;
        this.infinity = builder.infinity == null ? null : (Timestamp) builder.infinity.clone();
        this.gsis = Collections.unmodifiableList(new ArrayList<>(builder.gsis));
        this.avgItemBytes = builder.avgItemBytes;
    }

    public static Builder builder(EntityMapping entity) {
        return new Builder(entity);
    }

    public EntityMapping entity() {
        return entity;
    }

    public String className() {
        return entity.className();
    }

    public String simpleClassName() {
        String cn = entity.className();
        int dot = cn.lastIndexOf('.');
        return dot < 0 ? cn : cn.substring(dot + 1);
    }

    public String tableName() {
        return entity.tableName();
    }

    public TemporalMapping temporal() {
        return entity.temporal();
    }

    public String pkAttributeName() {
        return pkAttributeName;
    }

    public String skAttributeName() {
        return skAttributeName;
    }

    public String businessFromItemName() {
        return businessFromItemName;
    }

    public String businessThruItemName() {
        return businessThruItemName;
    }

    public String processingFromItemName() {
        return processingFromItemName;
    }

    public String processingThruItemName() {
        return processingThruItemName;
    }

    public String businessAsOfJavaName() {
        return businessAsOfJavaName;
    }

    public String processingAsOfJavaName() {
        return processingAsOfJavaName;
    }

    public String businessFromJavaName() {
        return businessFromJavaName;
    }

    public String processingFromJavaName() {
        return processingFromJavaName;
    }

    public boolean businessToInclusive() {
        return businessToInclusive;
    }

    public boolean processingToInclusive() {
        return processingToInclusive;
    }

    public Timestamp infinity() {
        return infinity == null ? null : (Timestamp) infinity.clone();
    }

    public List<GsiSpec> gsis() {
        return gsis;
    }

    public int avgItemBytes() {
        return avgItemBytes;
    }

    public List<AttributeMapping> primaryKeyAttributes() {
        return entity.primaryKeyAttributes();
    }

    public boolean isPrimaryKeyJavaName(String javaName) {
        for (AttributeMapping a : entity.primaryKeyAttributes()) {
            if (a.javaName().equals(javaName)) {
                return true;
            }
        }
        return false;
    }

    public boolean isFromColumnJavaName(String javaName) {
        return javaName != null && (javaName.equals(businessFromJavaName) || javaName.equals(processingFromJavaName));
    }

    public boolean isAsOfJavaName(String javaName) {
        return javaName != null && (javaName.equals(businessAsOfJavaName) || javaName.equals(processingAsOfJavaName));
    }

    public String itemNameForJava(String javaName) {
        if (javaName == null) {
            return null;
        }
        if (javaName.equals(businessFromJavaName)) {
            return businessFromItemName;
        }
        if (javaName.equals(processingFromJavaName)) {
            return processingFromItemName;
        }
        try {
            return entity.attribute(javaName).itemName();
        } catch (IllegalArgumentException e) {
            if ("businessDateTo".equals(javaName) || "businessDateThru".equals(javaName)) {
                return businessThruItemName;
            }
            if ("processingDateTo".equals(javaName) || "processingDateThru".equals(javaName)) {
                return processingThruItemName;
            }
            return javaName;
        }
    }

    public static final class Builder {
        private final EntityMapping entity;
        private String pkAttributeName = PK_ATTR;
        private String skAttributeName = SK_ATTR;
        private String businessFromItemName = "FROM_Z";
        private String businessThruItemName = "THRU_Z";
        private String processingFromItemName = "IN_Z";
        private String processingThruItemName = "OUT_Z";
        private String businessAsOfJavaName = "businessDate";
        private String processingAsOfJavaName = "processingDate";
        private String businessFromJavaName = "businessDateFrom";
        private String processingFromJavaName = "processingDateFrom";
        private boolean businessToInclusive = false;
        private boolean processingToInclusive = false;
        private Timestamp infinity;
        private final List<GsiSpec> gsis = new ArrayList<GsiSpec>();
        private int avgItemBytes = PlannerConfig.DEFAULT_AVG_ITEM_BYTES;

        private Builder(EntityMapping entity) {
            this.entity = entity;
            if (entity.temporal().infinity() != null) {
                this.infinity = entity.temporal().infinity();
            }
        }

        public Builder pkAttributeName(String v) {
            this.pkAttributeName = v;
            return this;
        }

        public Builder skAttributeName(String v) {
            this.skAttributeName = v;
            return this;
        }

        public Builder businessFromItemName(String v) {
            this.businessFromItemName = v;
            return this;
        }

        public Builder businessThruItemName(String v) {
            this.businessThruItemName = v;
            return this;
        }

        public Builder processingFromItemName(String v) {
            this.processingFromItemName = v;
            return this;
        }

        public Builder processingThruItemName(String v) {
            this.processingThruItemName = v;
            return this;
        }

        public Builder businessAsOfJavaName(String v) {
            this.businessAsOfJavaName = v;
            return this;
        }

        public Builder processingAsOfJavaName(String v) {
            this.processingAsOfJavaName = v;
            return this;
        }

        public Builder businessFromJavaName(String v) {
            this.businessFromJavaName = v;
            return this;
        }

        public Builder processingFromJavaName(String v) {
            this.processingFromJavaName = v;
            return this;
        }

        public Builder businessToInclusive(boolean v) {
            this.businessToInclusive = v;
            return this;
        }

        public Builder processingToInclusive(boolean v) {
            this.processingToInclusive = v;
            return this;
        }

        public Builder infinity(Timestamp v) {
            this.infinity = v;
            return this;
        }

        /**
         * Takes infinity from the entity's <b>generated attribute</b> rather than from the value the
         * XML parser reconstructed.
         *
         * <p>The parser cannot classload an {@code infinityDate="[...getDefaultInfinity()]"} snippet
         * and substitutes a conventional UTC sentinel; Reladomo's real value is in the JVM default
         * timezone. Those agree in UTC and nowhere else, and that disagreement caused finding 12 —
         * the current-row query silently returned no rows. Prefer this over {@link #infinity} for any
         * design built from a parsed mapping.
         */
        public Builder infinityFrom(com.gs.fw.common.mithra.finder.RelatedFinder finder) {
            Timestamp resolved = io.reladynamo.core.bridge.InfinityResolver.resolve(finder);
            if (resolved != null) {
                this.infinity = resolved;
            }
            return this;
        }

        public Builder addGsi(GsiSpec gsi) {
            this.gsis.add(Objects.requireNonNull(gsi, "gsi"));
            return this;
        }

        public Builder avgItemBytes(int v) {
            this.avgItemBytes = v;
            return this;
        }

        public PhysicalDesign build() {
            TemporalAttributeNames.requireResolvable(entity);
            if (entity.temporal().hasBusinessDate()) {
                this.businessFromJavaName = TemporalAttributeNames.businessFromJavaName(entity);
                this.businessFromItemName = entity.attribute(this.businessFromJavaName).itemName();
                this.businessThruItemName = entity.attribute(
                        TemporalAttributeNames.businessToJavaName(entity)).itemName();
                this.businessAsOfJavaName = TemporalAttributeNames.businessAsOfJavaName(entity);
            }
            if (entity.temporal().hasProcessingDate()) {
                this.processingFromJavaName = TemporalAttributeNames.processingFromJavaName(entity);
                this.processingFromItemName = entity.attribute(this.processingFromJavaName).itemName();
                this.processingThruItemName = entity.attribute(
                        TemporalAttributeNames.processingToJavaName(entity)).itemName();
                this.processingAsOfJavaName = TemporalAttributeNames.processingAsOfJavaName(entity);
            }
            return new PhysicalDesign(this);
        }
    }
}
