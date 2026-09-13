package top.cheesesmp.ddm.queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-server waiting line used to trickle players back onto a server that has
 * just come up, instead of letting the whole lobby slam it at once.
 *
 * <p>Pure bookkeeping - it never touches Velocity, which makes the ordering and
 * release-rate rules easy to test.
 */
public final class ReleaseQueue {

    /** One player's place in line. Lower {@code priority} goes first. */
    public record Entry(UUID player, int priority, long queuedAt) {
    }

    /** Players with no priority permission sit behind everyone who has one. */
    public static final int NO_PRIORITY = Integer.MAX_VALUE;

    private static final Comparator<Entry> ORDER = Comparator
            .comparingInt(Entry::priority)
            .thenComparingLong(Entry::queuedAt)
            .thenComparing(entry -> entry.player().toString());

    private final Map<String, List<Entry>> queues = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRelease = new ConcurrentHashMap<>();

    private List<Entry> queueFor(String server) {
        return queues.computeIfAbsent(server.toLowerCase(Locale.ROOT), key -> new ArrayList<>());
    }

    /**
     * Adds the player, or updates them in place if they are already queued.
     * Re-queuing after a failed attempt keeps the original {@code queuedAt} so
     * nobody loses their place because the server flickered.
     */
    public void enqueue(String server, UUID player, int priority, long queuedAt) {
        List<Entry> queue = queueFor(server);
        synchronized (queue) {
            queue.removeIf(entry -> entry.player().equals(player));
            queue.add(new Entry(player, priority, queuedAt));
            queue.sort(ORDER);
        }
    }

    public boolean contains(String server, UUID player) {
        List<Entry> queue = queueFor(server);
        synchronized (queue) {
            for (Entry entry : queue) {
                if (entry.player().equals(player)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops the player from every queue; safe to call for someone not queued. */
    public void remove(UUID player) {
        for (List<Entry> queue : queues.values()) {
            synchronized (queue) {
                queue.removeIf(entry -> entry.player().equals(player));
            }
        }
    }

    /** @return 1-based place in line, or 0 when the player is not queued */
    public int position(String server, UUID player) {
        List<Entry> queue = queueFor(server);
        synchronized (queue) {
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).player().equals(player)) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    public int size(String server) {
        List<Entry> queue = queueFor(server);
        synchronized (queue) {
            return queue.size();
        }
    }

    public List<Entry> view(String server) {
        List<Entry> queue = queueFor(server);
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    public Set<String> servers() {
        return new LinkedHashSet<>(queues.keySet());
    }

    /**
     * Takes the next batch off the front if enough time has passed since the
     * last release.
     *
     * @return the players to let through, possibly empty
     */
    public List<UUID> release(String server, int batchSize, long intervalMs, long now) {
        String key = server.toLowerCase(Locale.ROOT);
        Long previous = lastRelease.get(key);
        if (previous != null && now - previous < intervalMs) {
            return List.of();
        }

        List<Entry> queue = queueFor(server);
        List<UUID> released = new ArrayList<>(batchSize);
        synchronized (queue) {
            int take = Math.min(batchSize, queue.size());
            for (int i = 0; i < take; i++) {
                released.add(queue.remove(0).player());
            }
        }
        if (!released.isEmpty()) {
            lastRelease.put(key, now);
        }
        return released;
    }

    /**
     * Resets a server's release clock so the very first batch after a restart
     * goes out immediately rather than waiting out a stale interval.
     */
    public void resetReleaseClock(String server) {
        lastRelease.remove(server.toLowerCase(Locale.ROOT));
    }

    public void clear() {
        queues.clear();
        lastRelease.clear();
    }
}
