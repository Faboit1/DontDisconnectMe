package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.ConfigSection;
import top.cheesesmp.ddm.config.FilterSpec;

class FilterSpecTest {

    private static FilterSpec build(Map<String, Object> raw, List<String> warnings) {
        return FilterSpec.from(ConfigSection.of(raw), warnings);
    }

    private static FilterSpec defaultsFromBundledConfig() throws Exception {
        return BundledConfig.load().defaultProfile().filters();
    }

    @Test
    void punishmentsAreNeverIntercepted() throws Exception {
        FilterSpec filters = defaultsFromBundledConfig();
        assertEquals(FilterSpec.Decision.PASS_THROUGH, filters.decide("You are banned from this server!"));
        assertEquals(FilterSpec.Decision.PASS_THROUGH, filters.decide("You are not whitelisted on this server"));
        assertEquals(FilterSpec.Decision.PASS_THROUGH, filters.decide("Kicked by an operator."));
    }

    @Test
    void restartsSkipStraightToTheWaitingScreen() throws Exception {
        FilterSpec filters = defaultsFromBundledConfig();
        assertEquals(FilterSpec.Decision.RESTART, filters.decide("Server is restarting, please rejoin shortly"));
        assertEquals(FilterSpec.Decision.RESTART, filters.decide("Server closed"));
        assertEquals(FilterSpec.Decision.RESTART, filters.decide("Scheduled maintenance"));
    }

    @Test
    void ordinaryDropsGetTheInstantRetry() throws Exception {
        FilterSpec filters = defaultsFromBundledConfig();
        assertEquals(FilterSpec.Decision.RECONNECT, filters.decide("Timed out"));
        assertEquals(FilterSpec.Decision.RECONNECT, filters.decide("Internal Exception: java.io.IOException"));
    }

    @Test
    void emptyReasonFollowsItsOwnSwitch() {
        List<String> warnings = new ArrayList<>();
        assertEquals(FilterSpec.Decision.RECONNECT,
                build(Map.of("handle-empty-reason", true), warnings).decide(""));
        assertEquals(FilterSpec.Decision.PASS_THROUGH,
                build(Map.of("handle-empty-reason", false), warnings).decide("   "));
        assertEquals(FilterSpec.Decision.PASS_THROUGH,
                build(Map.of("handle-empty-reason", false), warnings).decide(null));
    }

    @Test
    void onlyReasonsActsAsAnAllowList() {
        FilterSpec filters = build(Map.of("only-reasons", List.of(".*timed out.*")), new ArrayList<>());
        assertEquals(FilterSpec.Decision.RECONNECT, filters.decide("Timed out"));
        assertEquals(FilterSpec.Decision.PASS_THROUGH, filters.decide("Some other problem"));
    }

    @Test
    void serverListsAreCaseInsensitive() {
        FilterSpec ignoring = build(Map.of("ignored-servers", List.of("Creative")), new ArrayList<>());
        assertFalse(ignoring.serverHandled("creative"));
        assertTrue(ignoring.serverHandled("survival"));

        FilterSpec allowList = build(Map.of("handled-servers", List.of("SURVIVAL")), new ArrayList<>());
        assertTrue(allowList.serverHandled("survival"));
        assertFalse(allowList.serverHandled("lobby"));
    }

    @Test
    void brokenRegexIsReportedAndSkippedRatherThanFatal() {
        List<String> warnings = new ArrayList<>();
        FilterSpec filters = build(Map.of("ignored-reasons", List.of("([unclosed", ".*banned.*")), warnings);

        assertEquals(1, warnings.size(), "the bad pattern is reported");
        assertTrue(warnings.get(0).contains("ignored-reasons"));
        assertEquals(FilterSpec.Decision.PASS_THROUGH, filters.decide("You are banned"),
                "the good pattern still works");
    }
}
