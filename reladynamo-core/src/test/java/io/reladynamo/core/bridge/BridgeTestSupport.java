package io.reladynamo.core.bridge;

/** Reuses the planner fixtures' Reladomo boot so the bridge tests run against real generated objects. */
final class BridgeTestSupport {
    private BridgeTestSupport() {
    }

    static void boot() {
        io.reladynamo.core.plan.PlanBootAccess.ensure();
    }
}
