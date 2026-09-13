package top.cheesesmp.ddm.config;

import java.util.Locale;

/** The stages a reconnect session moves through, and their config keys. */
public enum Phase {
    /** We have just taken the kick over; the reason is on screen. */
    KICKED,
    /** The target server is unreachable. Restart screen, patient retries. */
    WAITING,
    /** The target server is healthy again; the player is queued to go back. */
    RECONNECTING,
    /** They made it. */
    SUCCESS,
    /** We gave up. */
    FAILED;

    private final String configKey = name().toLowerCase(Locale.ROOT);

    public String configKey() {
        return configKey;
    }

    /** True once the session is over and nothing further will be attempted. */
    public boolean terminal() {
        return this == SUCCESS || this == FAILED;
    }
}
