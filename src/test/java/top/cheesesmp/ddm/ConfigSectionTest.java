package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.ConfigSection;

class ConfigSectionTest {

    private static ConfigSection sample() {
        return ConfigSection.of(Map.of(
                "general", Map.of("debug", true, "tick-interval-ms", 250),
                "queue", Map.of("batch-size", 2, "interval-ms", 500L, "names", List.of("a", "b"))));
    }

    @Test
    void readsDottedPaths() {
        ConfigSection config = sample();
        assertTrue(config.getBoolean("general.debug", false));
        assertEquals(250, config.getInt("general.tick-interval-ms", 0));
        assertEquals(500L, config.getLong("queue.interval-ms", 0L));
        assertEquals(List.of("a", "b"), config.getStringList("queue.names"));
    }

    @Test
    void missingPathsFallBackToDefaults() {
        ConfigSection config = sample();
        assertEquals("fallback", config.getString("nope.not.here", "fallback"));
        assertEquals(7, config.getInt("queue.missing", 7));
        assertFalse(config.has("general.nothing"));
        assertTrue(config.section("does.not.exist").isEmpty());
    }

    @Test
    void clampsOutOfRangeNumbers() {
        ConfigSection config = ConfigSection.of(Map.of("tick", 5, "huge", 10_000_000));
        assertEquals(50L, config.getLongClamped("tick", 250L, 50L, 5000L));
        assertEquals(5000L, config.getLongClamped("huge", 250L, 50L, 5000L));
    }

    @Test
    void deepMergeKeepsUntouchedBranchesAndReplacesLists() {
        Map<String, Object> base = Map.of(
                "queue", Map.of("batch-size", 2, "interval-ms", 500),
                "sounds", List.of("one", "two"));
        Map<String, Object> overlay = Map.of(
                "queue", Map.of("batch-size", 10),
                "sounds", List.of("only"));

        ConfigSection merged = ConfigSection.of(ConfigSection.deepMerge(base, overlay));

        assertEquals(10, merged.getInt("queue.batch-size", 0), "overlay wins");
        assertEquals(500, merged.getInt("queue.interval-ms", 0), "untouched keys survive");
        assertEquals(List.of("only"), merged.getStringList("sounds"), "lists replace, never merge");
    }
}
