package top.cheesesmp.ddm.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The fully resolved settings that apply to players coming from one backend
 * server. Per-server overrides are deep-merged on top of the globals before
 * this is built, so nothing here ever has to fall back at read time.
 */
public record ServerProfile(
        String name,
        FilterSpec filters,
        HoldSpec hold,
        ReconnectSpec reconnect,
        QueueSpec queue,
        Map<Phase, PhaseSpec> phases) {

    public static ServerProfile from(String name, ConfigSection root, List<String> warnings) {
        Map<Phase, PhaseSpec> phases = new EnumMap<>(Phase.class);
        ConfigSection phaseRoot = root.section("phases");
        for (Phase phase : Phase.values()) {
            phases.put(phase, PhaseSpec.from(phaseRoot.section(phase.configKey())));
        }
        return new ServerProfile(
                name,
                FilterSpec.from(root.section("filters"), warnings),
                HoldSpec.from(root.section("hold")),
                ReconnectSpec.from(root.section("reconnect")),
                QueueSpec.from(root.section("queue")),
                Map.copyOf(phases));
    }

    public PhaseSpec phase(Phase phase) {
        return phases.get(phase);
    }
}
