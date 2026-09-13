package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.ConfigSection;
import top.cheesesmp.ddm.config.Phase;
import top.cheesesmp.ddm.config.PhaseSpec;
import top.cheesesmp.ddm.config.PluginConfig;
import top.cheesesmp.ddm.config.ServerProfile;
import top.cheesesmp.ddm.config.SoundSpec;

/** Guards the shipped config.yml against typos that would only show up in game. */
class BundledConfigTest {

    @Test
    void shippedConfigParsesWithoutWarnings() throws Exception {
        PluginConfig config = BundledConfig.load();
        assertEquals(List.of(), config.warnings(), "no broken regexes in the defaults");
        assertFalse(config.outdated(), "config-version matches CURRENT_VERSION");
        assertEquals(250L, config.general().tickIntervalMs());
    }

    @Test
    void everyPhaseIsDefined() throws Exception {
        ServerProfile profile = BundledConfig.load().defaultProfile();
        for (Phase phase : Phase.values()) {
            assertNotNull(profile.phase(phase), phase + " is missing from config.yml");
        }
    }

    @Test
    void waitingPlaysOthersideOnLoopAndStopsItOnExit() throws Exception {
        PhaseSpec waiting = BundledConfig.load().defaultProfile().phase(Phase.WAITING);
        SoundSpec disc = waiting.sounds().get(0);

        assertEquals("minecraft:music_disc.otherside", disc.key().asString());
        assertTrue(disc.loops(), "the disc has to restart or the screen goes silent");
        assertTrue(disc.stopOnExit(), "otherwise it keeps playing after reconnecting");
        assertTrue(waiting.title().enabled(), "the downtime title is the point of this phase");
        assertTrue(waiting.title().stayMs() > waiting.updateIntervalMs(),
                "a title that expires between refreshes flickers");
    }

    @Test
    void reconnectingPlaysLavaChickenOnModernClientsAndAFallbackOnOldOnes() throws Exception {
        PhaseSpec reconnecting = BundledConfig.load().defaultProfile().phase(Phase.RECONNECTING);
        SoundSpec disc = reconnecting.sounds().get(0);
        SoundSpec fallback = reconnecting.sounds().get(1);

        assertEquals("minecraft:music_disc.lava_chicken", disc.key().asString());
        assertTrue(disc.stopOnExit());
        // Lava Chicken only exists from 1.21.5 (protocol 770) onwards.
        assertFalse(disc.appliesTo(769));
        assertTrue(disc.appliesTo(770));
        assertTrue(fallback.appliesTo(769), "older clients still get something");
        assertFalse(fallback.appliesTo(770), "and modern clients do not get both");
    }

    @Test
    void queueDefaultsToTwoPlayersEveryHalfSecond() throws Exception {
        var queue = BundledConfig.load().defaultProfile().queue();
        assertTrue(queue.enabled());
        assertEquals(2, queue.batchSize());
        assertEquals(500L, queue.intervalMs());
        assertEquals(0L, queue.etaMsFor(2), "the first batch leaves immediately");
        assertEquals(500L, queue.etaMsFor(3));
        assertEquals(1_000L, queue.etaMsFor(5));
    }

    @Test
    void retryLoopIsFourSecondsAndCannotBeConfiguredIntoASpamStorm() throws Exception {
        var retry = BundledConfig.load().defaultProfile().reconnect().retry();
        assertEquals(4_000L, retry.intervalMs());
        assertEquals(4_000L, retry.delayForAttempt(1));
        assertEquals(4_000L, retry.delayForAttempt(9), "no backoff by default");
        assertTrue(retry.onlyWhenOnline(), "we ping instead of hammering logins");
    }

    @Test
    void theMinimumIntervalFloorSurvivesASillyConfig() {
        ConfigSection silly = ConfigSection.of(Map.of(
                "reconnect", Map.of("retry", Map.of("interval-ms", 1, "min-interval-ms", 1))));
        var retry = ServerProfile.from("test", silly, new java.util.ArrayList<>()).reconnect().retry();

        assertEquals(250L, retry.minIntervalMs(), "clamped to the hard floor");
        assertTrue(retry.delayForAttempt(1) >= 250L);
    }

    @Test
    void backoffGrowsButRespectsItsCeiling() {
        ConfigSection raw = ConfigSection.of(Map.of("reconnect", Map.of("retry", Map.of(
                "interval-ms", 4000,
                "backoff", Map.of("enabled", true, "multiplier", 2.0, "max-interval-ms", 30000)))));
        var retry = ServerProfile.from("test", raw, new java.util.ArrayList<>()).reconnect().retry();

        assertEquals(4_000L, retry.delayForAttempt(1));
        assertEquals(8_000L, retry.delayForAttempt(2));
        assertEquals(16_000L, retry.delayForAttempt(3));
        assertEquals(30_000L, retry.delayForAttempt(9), "capped");
    }

    @Test
    void giveUpRulesOnlyApplyWhenConfigured() throws Exception {
        var retry = BundledConfig.load().defaultProfile().reconnect().retry();
        assertEquals(0, retry.maxAttempts());
        assertFalse(retry.attemptsExhausted(9_999), "0 means unlimited");
        assertTrue(retry.durationExhausted(retry.maxDurationMs() + 1));
        assertFalse(retry.durationExhausted(retry.maxDurationMs() - 1));
    }

    @Test
    void perServerOverridesInheritEverythingTheyDoNotMention() throws Exception {
        Map<String, Object> raw = BundledConfig.raw();
        raw.put("servers", Map.of(
                "survival", Map.of(
                        "queue", Map.of("batch-size", 1),
                        "reconnect", Map.of("retry", Map.of("interval-ms", 9000)))));

        PluginConfig config = PluginConfig.parse(raw);
        ServerProfile survival = config.profile("survival");
        ServerProfile lobby = config.profile("lobby");

        assertEquals(1, survival.queue().batchSize(), "the override applies");
        assertEquals(500L, survival.queue().intervalMs(), "unmentioned keys are inherited");
        assertEquals(9_000L, survival.reconnect().retry().intervalMs());
        assertEquals("minecraft:music_disc.otherside",
                survival.phase(Phase.WAITING).sounds().get(0).key().asString(),
                "phases are inherited too");

        assertEquals(2, lobby.queue().batchSize(), "other servers keep the globals");
        assertEquals(4_000L, lobby.reconnect().retry().intervalMs());
    }

    @Test
    void perServerOverrideCanDisableTheWholeFeature() throws Exception {
        Map<String, Object> raw = BundledConfig.raw();
        raw.put("servers", Map.of("creative", Map.of("reconnect", Map.of("enabled", false))));

        PluginConfig config = PluginConfig.parse(raw);
        assertFalse(config.profile("creative").reconnect().enabled());
        assertTrue(config.profile("survival").reconnect().enabled());
        assertTrue(config.profile("CREATIVE").reconnect().enabled() == false, "lookup is case-insensitive");
    }

    @Test
    void unknownServersFallBackToTheDefaultProfile() throws Exception {
        PluginConfig config = BundledConfig.load();
        assertEquals(config.defaultProfile(), config.profile("something-nobody-configured"));
        assertEquals(config.defaultProfile(), config.profile(null));
    }
}
