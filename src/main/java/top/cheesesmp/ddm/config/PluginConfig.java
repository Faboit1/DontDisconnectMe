package top.cheesesmp.ddm.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The whole config file, parsed once and then read-only. */
public final class PluginConfig {

    /** Bumped whenever new options ship, so outdated files can be flagged. */
    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final GeneralSpec general;
    private final WatcherSpec watcher;
    private final SimulateSpec simulate;
    private final ConfigSection messages;
    private final ServerProfile defaultProfile;
    private final Map<String, ServerProfile> overrides;
    private final List<String> warnings;

    private PluginConfig(int version, GeneralSpec general, WatcherSpec watcher, SimulateSpec simulate,
                         ConfigSection messages, ServerProfile defaultProfile,
                         Map<String, ServerProfile> overrides, List<String> warnings) {
        this.version = version;
        this.general = general;
        this.watcher = watcher;
        this.simulate = simulate;
        this.messages = messages;
        this.defaultProfile = defaultProfile;
        this.overrides = overrides;
        this.warnings = warnings;
    }

    public static PluginConfig parse(Map<String, Object> raw) {
        List<String> warnings = new java.util.ArrayList<>();
        ConfigSection root = ConfigSection.of(raw);

        ServerProfile defaults = ServerProfile.from("<default>", root, warnings);

        Map<String, ServerProfile> overrides = new LinkedHashMap<>();
        ConfigSection serverOverrides = root.section("servers");
        for (String serverName : serverOverrides.keys()) {
            ConfigSection override = serverOverrides.section(serverName);
            if (override.isEmpty()) {
                continue;
            }
            // Per-server blocks only list what they change; everything else is
            // inherited by merging them over the whole root document.
            ConfigSection merged = ConfigSection.of(
                    ConfigSection.deepMerge(root.raw(), override.raw()));
            overrides.put(serverName.toLowerCase(Locale.ROOT),
                    ServerProfile.from(serverName, merged, warnings));
        }

        return new PluginConfig(
                root.getInt("config-version", 0),
                GeneralSpec.from(root.section("general")),
                WatcherSpec.from(root.section("watcher")),
                SimulateSpec.from(root.section("simulate")),
                root.section("messages"),
                defaults,
                Map.copyOf(overrides),
                List.copyOf(warnings));
    }

    public int version() {
        return version;
    }

    public boolean outdated() {
        return version < CURRENT_VERSION;
    }

    public GeneralSpec general() {
        return general;
    }

    public WatcherSpec watcher() {
        return watcher;
    }

    public SimulateSpec simulate() {
        return simulate;
    }

    public List<String> warnings() {
        return warnings;
    }

    public ServerProfile defaultProfile() {
        return defaultProfile;
    }

    public Map<String, ServerProfile> overrides() {
        return overrides;
    }

    /** The settings to use for a player whose target server is {@code serverName}. */
    public ServerProfile profile(String serverName) {
        if (serverName == null) {
            return defaultProfile;
        }
        return overrides.getOrDefault(serverName.toLowerCase(Locale.ROOT), defaultProfile);
    }

    public String message(String key, String def) {
        return messages.getString(key, def);
    }

    public String prefix() {
        return messages.getString("prefix", "");
    }
}
