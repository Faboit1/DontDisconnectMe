package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.HoldSpec;
import top.cheesesmp.ddm.hold.SeamlessCoordinator;

/**
 * Skipping the client's loading screen is only safe when the backend really did
 * hand the player's entity id back, so these pin down when we refuse to.
 */
class SeamlessCoordinatorTest {

    private static final long MINUTE = 60_000L;

    private SeamlessCoordinator announced(String server, int remembersSeconds) {
        SeamlessCoordinator coordinator = new SeamlessCoordinator();
        coordinator.handleMessage(null, server,
                ("supported:" + remembersSeconds).getBytes(StandardCharsets.UTF_8));
        return coordinator;
    }

    @Test
    void refusesWithoutTheBackendPlugin() {
        SeamlessCoordinator coordinator = new SeamlessCoordinator();
        assertFalse(coordinator.canSkipLoadingScreen("survival", true, 1_000L, MINUTE),
                "nobody is handing entity ids back, so the client must reload");
    }

    @Test
    void allowsWhenTheBackendAnnouncedItself() {
        assertTrue(announced("survival", 60)
                .canSkipLoadingScreen("survival", true, 1_000L, MINUTE));
    }

    @Test
    void refusesForADifferentServer() {
        // Another server means another world; the client's chunks are wrong.
        assertFalse(announced("survival", 60)
                .canSkipLoadingScreen("survival", false, 1_000L, MINUTE));
    }

    @Test
    void refusesOnceThePlayerHasBeenAwayTooLong() {
        SeamlessCoordinator coordinator = announced("survival", 60);
        assertTrue(coordinator.canSkipLoadingScreen("survival", true, 59_000L, MINUTE));
        assertFalse(coordinator.canSkipLoadingScreen("survival", true, 61_000L, MINUTE));
    }

    @Test
    void takesTheStricterOfTheTwoLimits() {
        // Backend remembers for 10s; our config allows 60s. The backend wins.
        SeamlessCoordinator coordinator = announced("survival", 10);
        assertFalse(coordinator.canSkipLoadingScreen("survival", true, 20_000L, MINUTE));

        // Config allows 5s; backend remembers 60s. Our config wins.
        assertFalse(announced("survival", 60)
                .canSkipLoadingScreen("survival", true, 20_000L, 5_000L));
    }

    @Test
    void serverNamesAreCaseInsensitive() {
        assertTrue(announced("Survival", 60)
                .canSkipLoadingScreen("SURVIVAL", true, 1_000L, MINUTE));
    }

    @Test
    void seamlessIsOnByDefaultButOnlyDoesSomethingWithTheBackend() throws Exception {
        HoldSpec hold = BundledConfig.load().defaultProfile().hold();
        assertTrue(hold.seamlessEnabled());
        assertTrue(hold.seamlessMaxAwayMs() > 0L);
        // ...and with no backend announced, it still refuses.
        assertFalse(new SeamlessCoordinator()
                .canSkipLoadingScreen("survival", true, 0L, hold.seamlessMaxAwayMs()));
    }

    @Test
    void refusesToSkipForClientsThatGoThroughConfiguration() {
        // 1.20.2 and later park the client in configuration on every switch,
        // which throws the world away before the join-game we would be dropping.
        assertTrue(SeamlessCoordinator.rebuildsWorldOnSwitch(764));   // 1.20.2
        assertTrue(SeamlessCoordinator.rebuildsWorldOnSwitch(772));   // 1.21.8
        assertFalse(SeamlessCoordinator.rebuildsWorldOnSwitch(763));  // 1.20.1
        assertFalse(SeamlessCoordinator.rebuildsWorldOnSwitch(757));  // 1.18
    }
}
