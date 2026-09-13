package top.cheesesmp.ddm.config;

import java.util.List;

/**
 * Where a player is parked while their real server is away.
 *
 * <p>Velocity has no concept of a player attached to the proxy alone, so a
 * player kicked off their only server has to land somewhere - typically a small
 * limbo or lobby server.
 */
public record HoldSpec(
        Mode mode,
        long keepAliveIntervalMs,
        String consoleReason,
        boolean seamlessExperiment,
        List<String> servers,
        boolean onlyOnline,
        NoServerAction noServerAction,
        String noServerMessage,
        String redirectMessage,
        String notifyMessage) {

    public enum Mode {
        /**
         * Hold the player on the proxy itself. Their client keeps the world it
         * already has on screen and never learns anything happened, until they
         * are connected back to the real server.
         */
        FREEZE,
        /** Move the player to a hold server (limbo or lobby) instead. */
        SERVER
    }

    public enum NoServerAction {
        /** Kick the player with {@code no-server-message}. */
        DISCONNECT,
        /** Let the original kick happen untouched. */
        PASSTHROUGH
    }

    public static HoldSpec from(ConfigSection section) {
        ConfigSection freeze = section.section("freeze");
        return new HoldSpec(
                section.getEnum(Mode.class, "mode", Mode.FREEZE),
                freeze.getLongClamped("keep-alive-interval-ms", 10_000L, 1_000L, 25_000L),
                freeze.getString("console-reason", "held by DontDisconnectMe"),
                freeze.getBoolean("seamless-experiment", false),
                List.copyOf(section.getStringList("servers")),
                section.getBoolean("only-online", true),
                section.getEnum(NoServerAction.class, "no-server-action", NoServerAction.PASSTHROUGH),
                section.getString("no-server-message", ""),
                section.getString("redirect-message", ""),
                section.getString("notify-message", ""));
    }
}
