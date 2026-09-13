package top.cheesesmp.ddm.config;

import java.util.List;

/** Throttles how fast players are let back onto a freshly booted server. */
public record QueueSpec(
        boolean enabled,
        int batchSize,
        long intervalMs,
        long startDelayMs,
        String bypassPermission,
        List<String> priorityPermissions) {

    public static QueueSpec from(ConfigSection section) {
        return new QueueSpec(
                section.getBoolean("enabled", true),
                (int) section.getLongClamped("batch-size", 2L, 1L, 1000L),
                section.getLongClamped("interval-ms", 500L, 50L, 600_000L),
                section.getLongClamped("start-delay-ms", 3000L, 0L, 600_000L),
                section.getString("bypass-permission", "").trim(),
                List.copyOf(section.getStringList("priority-permissions")));
    }

    /** Rough wait for someone sitting at {@code position} (1-based). */
    public long etaMsFor(int position) {
        if (position <= batchSize) {
            return 0L;
        }
        long ticksAhead = (position - 1) / batchSize;
        return ticksAhead * intervalMs;
    }
}
