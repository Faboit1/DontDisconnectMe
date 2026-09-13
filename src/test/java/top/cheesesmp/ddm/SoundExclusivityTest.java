package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.ConfigSection;
import top.cheesesmp.ddm.config.Phase;
import top.cheesesmp.ddm.config.PhaseSpec;
import top.cheesesmp.ddm.config.ServerProfile;

/** Only one piece of music should ever be audible at a time. */
class SoundExclusivityTest {

    private static PhaseSpec phase(Phase phase) throws Exception {
        return BundledConfig.load().defaultProfile().phase(phase);
    }

    @Test
    void everyMusicPhaseSilencesWhatWasPlayingFirst() throws Exception {
        for (Phase phase : List.of(Phase.WAITING, Phase.RECONNECTING, Phase.SUCCESS)) {
            List<SoundStop> stops = phase(phase).stopSoundsFirst();
            assertTrue(stops.stream().anyMatch(stop -> stop.source() == Sound.Source.MUSIC),
                    phase + " should silence the game's background music");
            assertTrue(stops.stream().anyMatch(stop -> stop.source() == Sound.Source.RECORD),
                    phase + " should silence any disc already playing");
        }
    }

    @Test
    void theShortBlipPhaseDoesNotCutAnybodysMusic() throws Exception {
        assertEquals(List.of(), phase(Phase.KICKED).stopSoundsFirst(),
                "the kick blip is a one-shot; silencing music for it would be rude");
    }

    @Test
    void discsAreMarkedSoTheyCanBeStoppedAgain() throws Exception {
        assertTrue(phase(Phase.WAITING).sounds().get(0).stopOnExit());
        assertTrue(phase(Phase.RECONNECTING).sounds().get(0).stopOnExit());
    }

    @Test
    void allStopsEverything() {
        PhaseSpec spec = PhaseSpec.from(ConfigSection.of(Map.of("stop-sounds-first", List.of("ALL"))));
        assertEquals(1, spec.stopSoundsFirst().size());
        SoundStop stop = spec.stopSoundsFirst().get(0);
        assertEquals(null, stop.source(), "a blanket stop names no source");
        assertEquals(null, stop.sound(), "and no specific sound");
    }

    @Test
    void unknownSourcesAreSkippedRatherThanBreakingThePhase() {
        PhaseSpec spec = PhaseSpec.from(ConfigSection.of(Map.of(
                "stop-sounds-first", List.of("MUSIC", "not-a-source", "record"))));

        assertEquals(2, spec.stopSoundsFirst().size(), "the bad entry is dropped");
        assertTrue(spec.stopSoundsFirst().stream().anyMatch(s -> s.source() == Sound.Source.MUSIC));
        assertTrue(spec.stopSoundsFirst().stream().anyMatch(s -> s.source() == Sound.Source.RECORD),
                "lower case names still work");
    }

    @Test
    void aPhaseWithNothingConfiguredSilencesNothing() {
        assertEquals(List.of(), PhaseSpec.from(ConfigSection.empty()).stopSoundsFirst());
    }

    @Test
    void perServerOverridesCanChangeWhatIsSilenced() throws Exception {
        Map<String, Object> raw = BundledConfig.raw();
        raw.put("servers", Map.of("quiet", Map.of(
                "phases", Map.of("waiting", Map.of("stop-sounds-first", List.of("ALL"))))));

        ServerProfile quiet = top.cheesesmp.ddm.config.PluginConfig.parse(raw).profile("quiet");
        List<SoundStop> stops = quiet.phase(Phase.WAITING).stopSoundsFirst();

        assertEquals(1, stops.size());
        assertEquals(null, stops.get(0).source());
        assertFalse(quiet.phase(Phase.WAITING).sounds().isEmpty(), "the disc is still inherited");
    }
}
