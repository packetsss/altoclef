package adris.altoclef.cambridge;

/**
 * Canonical event identifiers emitted by CamBridge.
 */
public enum CamEventType {
    PHASE_ENTER(true, "P1"),
    PHASE_EXIT(true, "P2"),
    TASK_START(true, "P1"),
    TASK_END(true, "P1"),
    MILESTONE(false, "P2"),
    HAZARD(true, "P1"),
    REROUTE(true, "P1"),
    STALL(true, "P2"),
    DIM_CHANGE(true, "P2"),
    HEARTBEAT(false, "P3"),
    ACTIVITY_CONTEXT(false, "P1"),
    MINI_HUD(false, "P3"),
    DIAGNOSTIC(false, "P3");

    private final boolean big;
    private final String defaultPriority;

    CamEventType(boolean big, String defaultPriority) {
        this.big = big;
        this.defaultPriority = defaultPriority;
    }

    public boolean isBig() {
        return big;
    }

    public String getDefaultPriority() {
        return defaultPriority;
    }
}
