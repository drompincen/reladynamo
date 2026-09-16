package io.reladynamo.core.plan;

/**
 * Why the persister asked for a plan. Java 11: not a sealed type; closed by the private constructor.
 */
public final class PlanningPurpose {

    public static final PlanningPurpose FIND = new PlanningPurpose("FIND");
    public static final PlanningPurpose COUNT = new PlanningPurpose("COUNT");
    public static final PlanningPurpose CURSOR = new PlanningPurpose("CURSOR");
    public static final PlanningPurpose AGGREGATE = new PlanningPurpose("AGGREGATE");
    public static final PlanningPurpose DELETE = new PlanningPurpose("DELETE");
    public static final PlanningPurpose REFRESH = new PlanningPurpose("REFRESH");
    public static final PlanningPurpose DATE_RANGE = new PlanningPurpose("DATE_RANGE");
    public static final PlanningPurpose CACHE_LOAD = new PlanningPurpose("CACHE_LOAD");
    public static final PlanningPurpose COMPUTE_FUNCTION = new PlanningPurpose("COMPUTE_FUNCTION");

    private final String name;

    private PlanningPurpose(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public boolean forbidsGsi() {
        return this == REFRESH || this == DATE_RANGE || this == DELETE;
    }

    @Override
    public String toString() {
        return name;
    }
}
