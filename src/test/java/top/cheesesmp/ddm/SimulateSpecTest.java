package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.ConfigSection;
import top.cheesesmp.ddm.config.Phase;
import top.cheesesmp.ddm.config.SimulateSpec;

class SimulateSpecTest {

    @Test
    void defaultScriptWalksEveryPhaseInOrder() throws Exception {
        SimulateSpec spec = BundledConfig.load().simulate();

        assertEquals(Phase.KICKED, spec.phaseAt(0L));
        assertEquals(Phase.KICKED, spec.phaseAt(1_999L));
        assertEquals(Phase.WAITING, spec.phaseAt(2_000L));
        assertEquals(Phase.WAITING, spec.phaseAt(13_999L));
        assertEquals(Phase.RECONNECTING, spec.phaseAt(14_000L));
        assertEquals(Phase.RECONNECTING, spec.phaseAt(19_999L));
        assertEquals(Phase.SUCCESS, spec.phaseAt(20_000L));
        assertEquals(Phase.SUCCESS, spec.phaseAt(600_000L), "it always ends, never hangs");
    }

    @Test
    void aPhaseSetToZeroSecondsIsSkipped() {
        SimulateSpec spec = SimulateSpec.from(ConfigSection.of(Map.of(
                "kicked-seconds", 0, "waiting-seconds", 5, "reconnecting-seconds", 0)));

        assertEquals(Phase.WAITING, spec.phaseAt(0L));
        assertEquals(Phase.SUCCESS, spec.phaseAt(5_000L));
    }
}
