package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;

import java.util.Objects;

public final class PlanningRequest {

    private final AnalyzedOperation analyzedOperation;
    private final OrderBy orderBy;
    private final PhysicalDesign design;
    private final PlannerConfig config;
    private final int rowcount;
    private final int numberOfThreads;
    private final PlanningPurpose purpose;

    public PlanningRequest(AnalyzedOperation analyzedOperation, OrderBy orderBy,
                           PhysicalDesign design, PlannerConfig config, int rowcount,
                           int numberOfThreads, PlanningPurpose purpose) {
        this.analyzedOperation = Objects.requireNonNull(analyzedOperation, "analyzedOperation");
        this.orderBy = orderBy;
        this.design = Objects.requireNonNull(design, "design");
        this.config = Objects.requireNonNull(config, "config");
        this.rowcount = rowcount;
        this.numberOfThreads = numberOfThreads;
        this.purpose = Objects.requireNonNull(purpose, "purpose");
    }

    public AnalyzedOperation analyzedOperation() {
        return analyzedOperation;
    }

    public OrderBy orderBy() {
        return orderBy;
    }

    public PhysicalDesign design() {
        return design;
    }

    public PlannerConfig config() {
        return config;
    }

    public int rowcount() {
        return rowcount;
    }

    public int numberOfThreads() {
        return numberOfThreads;
    }

    public PlanningPurpose purpose() {
        return purpose;
    }
}
