package top.cheesesmp.ddm.config;

import java.util.List;

/** Timing and give-up rules for the reconnect loop. */
public record ReconnectSpec(
        boolean enabled,
        Immediate immediate,
        Retry retry,
        long sessionMemoryMs,
        boolean cancelOnManualConnect,
        boolean stopMusicOnSuccess,
        List<String> extraStopKeys) {

    /** The quick "your connection blipped" attempt made before any restart screen. */
    public record Immediate(boolean enabled, long delayMs, int maxAttempts) {
        static Immediate from(ConfigSection section) {
            return new Immediate(
                    section.getBoolean("enabled", true),
                    section.getLongClamped("delay-ms", 500L, 0L, 60_000L),
                    (int) section.getLongClamped("max-attempts", 1L, 0L, 100L));
        }
    }

    public record Retry(
            long intervalMs,
            long minIntervalMs,
            boolean backoffEnabled,
            double backoffMultiplier,
            long backoffMaxIntervalMs,
            boolean onlyWhenOnline,
            int maxAttempts,
            long maxDurationMs,
            GiveUpAction onGiveUp,
            String giveUpMessage) {

        static Retry from(ConfigSection section) {
            long min = section.getLongClamped("min-interval-ms", 1000L, 250L, 600_000L);
            long interval = Math.max(min, section.getLongClamped("interval-ms", 4000L, 250L, 600_000L));
            ConfigSection backoff = section.section("backoff");
            return new Retry(
                    interval,
                    min,
                    backoff.getBoolean("enabled", false),
                    Math.max(1.0D, backoff.getDouble("multiplier", 1.5D)),
                    Math.max(interval, backoff.getLong("max-interval-ms", 30_000L)),
                    section.getBoolean("only-when-online", true),
                    (int) section.getLongClamped("max-attempts", 0L, 0L, 100_000L),
                    Math.max(0L, section.getLong("max-duration-seconds", 900L)) * 1000L,
                    section.getEnum(GiveUpAction.class, "on-give-up", GiveUpAction.STAY),
                    section.getString("give-up-message", ""));
        }

        /**
         * Delay before attempt number {@code attempt} (1-based). Never returns less
         * than {@link #minIntervalMs()}, whatever the config says.
         */
        public long delayForAttempt(int attempt) {
            if (!backoffEnabled || attempt <= 1) {
                return Math.max(minIntervalMs, intervalMs);
            }
            double scaled = intervalMs * Math.pow(backoffMultiplier, attempt - 1);
            long capped = (long) Math.min(scaled, (double) backoffMaxIntervalMs);
            return Math.max(minIntervalMs, capped);
        }

        public boolean attemptsExhausted(int attempts) {
            return maxAttempts > 0 && attempts >= maxAttempts;
        }

        public boolean durationExhausted(long elapsedMs) {
            return maxDurationMs > 0L && elapsedMs >= maxDurationMs;
        }
    }

    public enum GiveUpAction {
        /** Kick the player with {@code give-up-message}. */
        DISCONNECT,
        /** Leave them on the hold server and stop trying. */
        STAY
    }

    public static ReconnectSpec from(ConfigSection section) {
        return new ReconnectSpec(
                section.getBoolean("enabled", true),
                Immediate.from(section.section("immediate")),
                Retry.from(section.section("retry")),
                Math.max(0L, section.getLong("session-memory-seconds", 30L)) * 1000L,
                section.getBoolean("cancel-on-manual-connect", true),
                section.getBoolean("stop-music-on-success", true),
                List.copyOf(section.getStringList("extra-stop-keys")));
    }
}
