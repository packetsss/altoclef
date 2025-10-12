package adris.altoclef.cambridge;

/**
 * Controls how much telemetry CamBridge emits.
 */
public enum CamBridgeMode {
    FULL,
    STATUS_ONLY;

    public static CamBridgeMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        return switch (raw.trim().toLowerCase()) {
            case "status", "status-only", "status_only", "lite", "light", "minimal" -> STATUS_ONLY;
            default -> FULL;
        };
    }

    public boolean isStatusOnly() {
        return this == STATUS_ONLY;
    }

    public boolean isFull() {
        return this == FULL;
    }
}
