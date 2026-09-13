package top.cheesesmp.ddm.config;

/**
 * Timings for {@code /ddm simulate}, which walks a player through every screen
 * without touching a real server.
 */
public record SimulateSpec(long kickedMs, long waitingMs, long reconnectingMs) {

    public static SimulateSpec from(ConfigSection section) {
        long kicked = Math.max(0L, section.getLong("kicked-seconds", 2L)) * 1000L;
        long waiting = kicked + Math.max(0L, section.getLong("waiting-seconds", 12L)) * 1000L;
        long reconnecting = waiting + Math.max(0L, section.getLong("reconnecting-seconds", 6L)) * 1000L;
        return new SimulateSpec(kicked, waiting, reconnecting);
    }

    /** The phase a preview that has been running for {@code elapsedMs} should be in. */
    public Phase phaseAt(long elapsedMs) {
        if (elapsedMs < kickedMs) {
            return Phase.KICKED;
        }
        if (elapsedMs < waitingMs) {
            return Phase.WAITING;
        }
        if (elapsedMs < reconnectingMs) {
            return Phase.RECONNECTING;
        }
        return Phase.SUCCESS;
    }
}
