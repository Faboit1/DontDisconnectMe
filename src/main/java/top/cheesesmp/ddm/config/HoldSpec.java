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
        List<String> servers,
        boolean onlyOnline,
        NoServerAction noServerAction,
        String noServerMessage,
        String redirectMessage,
        String notifyMessage) {

    public enum NoServerAction {
        /** Kick the player with {@code no-server-message}. */
        DISCONNECT,
        /** Let the original kick happen untouched. */
        PASSTHROUGH
    }

    public static HoldSpec from(ConfigSection section) {
        return new HoldSpec(
                List.copyOf(section.getStringList("servers")),
                section.getBoolean("only-online", true),
                section.getEnum(NoServerAction.class, "no-server-action", NoServerAction.PASSTHROUGH),
                section.getString("no-server-message", ""),
                section.getString("redirect-message", ""),
                section.getString("notify-message", ""));
    }
}
