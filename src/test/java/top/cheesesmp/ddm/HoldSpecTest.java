package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.ConfigSection;
import top.cheesesmp.ddm.config.HoldSpec;
import top.cheesesmp.ddm.config.PluginConfig;

class HoldSpecTest {

    @Test
    void holdingOnTheProxyIsTheDefault() throws Exception {
        HoldSpec hold = BundledConfig.load().defaultProfile().hold();
        assertEquals(HoldSpec.Mode.FREEZE, hold.mode());
        assertEquals(10_000L, hold.keepAliveIntervalMs());
        assertTrue(hold.servers().contains("limbo"), "a fallback is still configured");
    }

    @Test
    void keepAliveCannotBeSetAboveTheClientTimeout() {
        // Vanilla drops a connection that has been silent for 30s, so a held
        // player has to hear from us well before that however the file is edited.
        HoldSpec hold = HoldSpec.from(ConfigSection.of(Map.of(
                "freeze", Map.of("keep-alive-interval-ms", 120_000))));
        assertTrue(hold.keepAliveIntervalMs() <= 25_000L,
                "expected a safe interval, got " + hold.keepAliveIntervalMs());
    }

    @Test
    void keepAliveCannotBeSetToAFloodEither() {
        HoldSpec hold = HoldSpec.from(ConfigSection.of(Map.of(
                "freeze", Map.of("keep-alive-interval-ms", 1))));
        assertTrue(hold.keepAliveIntervalMs() >= 1_000L);
    }

    @Test
    void anUnknownModeFallsBackToFreezeRatherThanFailing() {
        HoldSpec hold = HoldSpec.from(ConfigSection.of(Map.of("mode", "nonsense")));
        assertEquals(HoldSpec.Mode.FREEZE, hold.mode());
    }

    @Test
    void modeIsOverridablePerServer() throws Exception {
        Map<String, Object> raw = BundledConfig.raw();
        raw.put("servers", Map.of("survival", Map.of("hold", Map.of("mode", "SERVER"))));

        PluginConfig config = PluginConfig.parse(raw);

        assertEquals(HoldSpec.Mode.SERVER, config.profile("survival").hold().mode());
        assertEquals(HoldSpec.Mode.FREEZE, config.profile("lobby").hold().mode());
        assertEquals(10_000L, config.profile("survival").hold().keepAliveIntervalMs(),
                "unmentioned freeze settings are still inherited");
    }
}
