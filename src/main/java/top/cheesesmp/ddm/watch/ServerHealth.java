package top.cheesesmp.ddm.watch;

/** Immutable view of one backend server's health, safe to hand to commands. */
public record ServerHealth(Status status, long downSince, long upSince, long lastCheckAt, long lastLatencyMs) {

    public enum Status {
        /** Never successfully checked yet. */
        UNKNOWN,
        ONLINE,
        OFFLINE
    }

    public static ServerHealth unknown() {
        return new ServerHealth(Status.UNKNOWN, 0L, 0L, 0L, -1L);
    }

    public boolean online() {
        return status == Status.ONLINE;
    }

    public boolean offline() {
        return status == Status.OFFLINE;
    }

    /** How long the server has been unreachable, or 0 when it is up. */
    public long downtimeMs(long now) {
        return status == Status.OFFLINE && downSince > 0L ? Math.max(0L, now - downSince) : 0L;
    }

    /** How long the server has been healthy, or 0 when it is down. */
    public long uptimeMs(long now) {
        return status == Status.ONLINE && upSince > 0L ? Math.max(0L, now - upSince) : 0L;
    }
}
