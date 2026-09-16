package io.reladynamo.core.plan;

/** Exposes the planner fixtures' Reladomo boot to tests in sibling packages. */
public final class PlanBootAccess {
    private PlanBootAccess() {
    }

    public static void ensure() {
        PlanReladomoBoot.ensure();
    }
}
