package top.cheesesmp.ddm.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Decides which kicks the plugin is allowed to take over. */
public record FilterSpec(
        String requirePermission,
        Set<String> ignoredServers,
        Set<String> handledServers,
        List<Pattern> ignoredReasons,
        List<Pattern> restartReasons,
        List<Pattern> onlyReasons,
        boolean handleEmptyReason) {

    /** What to do with a kick, based purely on its reason text. */
    public enum Decision {
        /** Not ours - let the player be kicked for real. */
        PASS_THROUGH,
        /** Ours, and worth one instant retry first. */
        RECONNECT,
        /** Ours, but the server is on its way down - skip straight to waiting. */
        RESTART
    }

    public static FilterSpec from(ConfigSection section, List<String> warnings) {
        return new FilterSpec(
                section.getString("require-permission", "").trim(),
                lowerSet(section.getStringList("ignored-servers")),
                lowerSet(section.getStringList("handled-servers")),
                compile(section.getStringList("ignored-reasons"), "ignored-reasons", warnings),
                compile(section.getStringList("restart-reasons"), "restart-reasons", warnings),
                compile(section.getStringList("only-reasons"), "only-reasons", warnings),
                section.getBoolean("handle-empty-reason", true));
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    private static List<Pattern> compile(List<String> raw, String where, List<String> warnings) {
        List<Pattern> out = new ArrayList<>(raw.size());
        for (String pattern : raw) {
            try {
                out.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            } catch (PatternSyntaxException ex) {
                warnings.add("filters." + where + ": ignoring invalid regex '" + pattern + "' (" + ex.getDescription() + ")");
            }
        }
        return List.copyOf(out);
    }

    public boolean serverHandled(String serverName) {
        String lower = serverName.toLowerCase(Locale.ROOT);
        if (ignoredServers.contains(lower)) {
            return false;
        }
        return handledServers.isEmpty() || handledServers.contains(lower);
    }

    /**
     * @param plainReason the kick message with all formatting stripped; may be blank
     */
    public Decision decide(String plainReason) {
        String reason = plainReason == null ? "" : plainReason.trim();

        if (reason.isEmpty()) {
            return handleEmptyReason ? Decision.RECONNECT : Decision.PASS_THROUGH;
        }
        if (matchesAny(ignoredReasons, reason)) {
            return Decision.PASS_THROUGH;
        }
        if (!onlyReasons.isEmpty() && !matchesAny(onlyReasons, reason)) {
            return Decision.PASS_THROUGH;
        }
        if (matchesAny(restartReasons, reason)) {
            return Decision.RESTART;
        }
        return Decision.RECONNECT;
    }

    private static boolean matchesAny(List<Pattern> patterns, String value) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }
}
