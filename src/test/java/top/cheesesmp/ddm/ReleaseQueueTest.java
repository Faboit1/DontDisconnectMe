package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.queue.ReleaseQueue;

class ReleaseQueueTest {

    private static final String SERVER = "survival";

    private final ReleaseQueue queue = new ReleaseQueue();
    private final UUID alice = UUID.nameUUIDFromBytes("alice".getBytes());
    private final UUID bob = UUID.nameUUIDFromBytes("bob".getBytes());
    private final UUID carol = UUID.nameUUIDFromBytes("carol".getBytes());

    @Test
    void firstKickedIsFirstBack() {
        queue.enqueue(SERVER, alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        queue.enqueue(SERVER, bob, ReleaseQueue.NO_PRIORITY, 2_000L);

        assertEquals(1, queue.position(SERVER, alice));
        assertEquals(2, queue.position(SERVER, bob));
        assertEquals(2, queue.size(SERVER));
    }

    @Test
    void priorityJumpsTheLineRegardlessOfArrivalTime() {
        queue.enqueue(SERVER, alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        queue.enqueue(SERVER, bob, 1, 5_000L);
        queue.enqueue(SERVER, carol, 0, 9_000L);

        assertEquals(1, queue.position(SERVER, carol), "highest priority first");
        assertEquals(2, queue.position(SERVER, bob));
        assertEquals(3, queue.position(SERVER, alice));
    }

    @Test
    void releasesTwoPlayersEveryHalfSecond() {
        long now = 10_000L;
        for (int i = 0; i < 5; i++) {
            queue.enqueue(SERVER, UUID.nameUUIDFromBytes(("p" + i).getBytes()),
                    ReleaseQueue.NO_PRIORITY, now + i);
        }

        assertEquals(2, queue.release(SERVER, 2, 500L, now).size(), "first batch goes immediately");
        assertTrue(queue.release(SERVER, 2, 500L, now + 200L).isEmpty(), "too soon for the next batch");
        assertEquals(2, queue.release(SERVER, 2, 500L, now + 500L).size());
        assertEquals(1, queue.release(SERVER, 2, 500L, now + 1_000L).size(), "last, partial batch");
        assertTrue(queue.release(SERVER, 2, 500L, now + 1_500L).isEmpty());
        assertEquals(0, queue.size(SERVER));
    }

    @Test
    void requeuingAfterAFailedAttemptKeepsThePlayersPlace() {
        queue.enqueue(SERVER, alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        queue.enqueue(SERVER, bob, ReleaseQueue.NO_PRIORITY, 2_000L);

        List<UUID> released = queue.release(SERVER, 1, 500L, 5_000L);
        assertEquals(List.of(alice), released);

        // The attempt failed, so she goes back in with her original timestamp.
        queue.enqueue(SERVER, alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        assertEquals(1, queue.position(SERVER, alice), "she is not sent to the back");
    }

    @Test
    void enqueueingTwiceDoesNotDuplicate() {
        queue.enqueue(SERVER, alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        queue.enqueue(SERVER, alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        assertEquals(1, queue.size(SERVER));
    }

    @Test
    void removingAPlayerClearsThemFromEveryServer() {
        queue.enqueue(SERVER, alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        queue.enqueue("creative", alice, ReleaseQueue.NO_PRIORITY, 1_000L);

        queue.remove(alice);

        assertFalse(queue.contains(SERVER, alice));
        assertFalse(queue.contains("creative", alice));
        assertEquals(0, queue.position(SERVER, alice));
    }

    @Test
    void serverNamesAreCaseInsensitive() {
        queue.enqueue("Survival", alice, ReleaseQueue.NO_PRIORITY, 1_000L);
        assertTrue(queue.contains("survival", alice));
        assertEquals(1, queue.size("SURVIVAL"));
    }
}
