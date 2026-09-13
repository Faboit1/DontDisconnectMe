package top.cheesesmp.ddm.config;

/** How aggressively backend servers are health-checked. */
public record WatcherSpec(
        Mode mode,
        long intervalUpMs,
        long intervalDownMs,
        long pingTimeoutMs,
        int failThreshold,
        int successThreshold,
        boolean seedDownOnKick,
        long lingerMs) {

    public enum Mode {
        /** Only watch servers somebody is waiting for. */
        SMART,
        /** Watch every registered server, always. */
        ALWAYS
    }

    public static WatcherSpec from(ConfigSection section) {
        return new WatcherSpec(
                section.getEnum(Mode.class, "mode", Mode.SMART),
                section.getLongClamped("interval-up-ms", 5000L, 250L, 600_000L),
                section.getLongClamped("interval-down-ms", 2000L, 250L, 600_000L),
                section.getLongClamped("ping-timeout-ms", 2500L, 250L, 60_000L),
                (int) section.getLongClamped("fail-threshold", 1L, 1L, 100L),
                (int) section.getLongClamped("success-threshold", 2L, 1L, 100L),
                section.getBoolean("seed-down-on-kick", true),
                Math.max(0L, section.getLong("linger-seconds", 60L)) * 1000L);
    }
}
