package top.cheesesmp.ddm.watch;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import top.cheesesmp.ddm.config.WatcherSpec;

/**
 * Pings backend servers and remembers when each one went away.
 *
 * <p>This is what keeps the plugin from spam-reconnecting: while a server is
 * down we send it a cheap status ping every couple of seconds instead of a full
 * login attempt, and the shared downtime clock it produces is what every
 * waiting player's "restarting for XX" display reads from.
 */
public final class ServerWatcher {

    private final ProxyServer proxy;
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private volatile WatcherSpec spec;
    private volatile BooleanSupplier debug = () -> false;
    private volatile DebugSink debugSink = message -> { };

    /** Where debug lines go; swapped in by the plugin so this class stays testable. */
    @FunctionalInterface
    public interface DebugSink {
        void log(String message);
    }

    public ServerWatcher(ProxyServer proxy, WatcherSpec spec) {
        this.proxy = proxy;
        this.spec = spec;
    }

    public void configure(WatcherSpec spec, BooleanSupplier debug, DebugSink sink) {
        this.spec = spec;
        this.debug = debug;
        this.debugSink = sink;
    }

    /** Forget everything; used on reload so stale state cannot linger. */
    public void reset() {
        tracked.clear();
    }

    private Tracked track(String serverName) {
        return tracked.computeIfAbsent(serverName.toLowerCase(Locale.ROOT), key -> new Tracked(serverName));
    }

    /**
     * Tell the watcher somebody cares about this server right now. In SMART mode
     * only servers with recent interest (or recent failures) are pinged.
     */
    public void markInterest(String serverName, long now) {
        track(serverName).interestUntil = now + spec.lingerMs();
    }

    /**
     * Treat a server as down as of {@code now} without waiting for a ping to
     * fail, so the downtime clock starts at the moment of the kick.
     */
    public void seedOffline(String serverName, long now) {
        Tracked entry = track(serverName);
        synchronized (entry) {
            if (entry.status != ServerHealth.Status.OFFLINE) {
                entry.status = ServerHealth.Status.OFFLINE;
                entry.downSince = now;
                entry.upSince = 0L;
                entry.successes = 0;
                entry.failures = Math.max(entry.failures, spec.failThreshold());
                debugLog(entry.name + " assumed OFFLINE (player kicked)");
            }
        }
        entry.interestUntil = now + spec.lingerMs();
    }

    public ServerHealth health(String serverName) {
        Tracked entry = tracked.get(serverName.toLowerCase(Locale.ROOT));
        return entry == null ? ServerHealth.unknown() : entry.snapshot();
    }

    public boolean isOnline(String serverName) {
        return health(serverName).online();
    }

    /** Every server the watcher currently has an opinion about. */
    public Map<String, ServerHealth> allHealth() {
        Map<String, ServerHealth> out = new LinkedHashMap<>();
        for (Tracked entry : tracked.values()) {
            out.put(entry.name, entry.snapshot());
        }
        return out;
    }

    /** Runs on the plugin clock; issues whatever pings are due. */
    public void tick(long now) {
        WatcherSpec current = spec;

        if (current.mode() == WatcherSpec.Mode.ALWAYS) {
            for (RegisteredServer server : proxy.getAllServers()) {
                track(server.getServerInfo().getName()).interestUntil = now + current.lingerMs();
            }
        }

        for (Tracked entry : tracked.values()) {
            if (current.mode() == WatcherSpec.Mode.SMART && now > entry.interestUntil) {
                continue;
            }
            long interval = entry.status == ServerHealth.Status.ONLINE
                    ? current.intervalUpMs()
                    : current.intervalDownMs();
            if (now - entry.lastCheckAt < interval) {
                continue;
            }
            ping(entry, current, now);
        }
    }

    private void ping(Tracked entry, WatcherSpec current, long now) {
        Optional<RegisteredServer> server = proxy.getServer(entry.name);
        if (server.isEmpty()) {
            // The server was removed from velocity.toml - stop pretending we know.
            recordFailure(entry, current, now);
            return;
        }
        if (!entry.pingInFlight.compareAndSet(false, true)) {
            return;
        }
        entry.lastCheckAt = now;
        long startedAt = System.currentTimeMillis();
        server.get().ping()
                .orTimeout(current.pingTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((ping, error) -> {
                    entry.pingInFlight.set(false);
                    long finishedAt = System.currentTimeMillis();
                    if (error != null || ping == null) {
                        recordFailure(entry, spec, finishedAt);
                    } else {
                        entry.lastLatencyMs = finishedAt - startedAt;
                        recordSuccess(entry, spec, finishedAt);
                    }
                });
    }

    private void recordFailure(Tracked entry, WatcherSpec current, long now) {
        synchronized (entry) {
            entry.successes = 0;
            entry.failures++;
            if (entry.firstFailureAt == 0L) {
                entry.firstFailureAt = now;
            }
            if (entry.status != ServerHealth.Status.OFFLINE && entry.failures >= current.failThreshold()) {
                entry.status = ServerHealth.Status.OFFLINE;
                entry.downSince = entry.firstFailureAt;
                entry.upSince = 0L;
                debugLog(entry.name + " is now OFFLINE");
            }
        }
    }

    private void recordSuccess(Tracked entry, WatcherSpec current, long now) {
        synchronized (entry) {
            entry.failures = 0;
            entry.firstFailureAt = 0L;
            entry.successes++;
            if (entry.status != ServerHealth.Status.ONLINE && entry.successes >= current.successThreshold()) {
                entry.status = ServerHealth.Status.ONLINE;
                entry.upSince = now;
                entry.downSince = 0L;
                debugLog(entry.name + " is now ONLINE");
            }
        }
    }

    private void debugLog(String message) {
        if (debug.getAsBoolean()) {
            debugSink.log(message);
        }
    }

    public Collection<String> watchedNames() {
        return allHealth().keySet();
    }

    /** Mutable per-server state. Guarded by its own monitor for the counters. */
    private static final class Tracked {
        private final String name;
        private final AtomicBoolean pingInFlight = new AtomicBoolean();
        private volatile ServerHealth.Status status = ServerHealth.Status.UNKNOWN;
        private volatile long downSince;
        private volatile long upSince;
        private volatile long lastCheckAt;
        private volatile long lastLatencyMs = -1L;
        private volatile long interestUntil;
        private volatile long firstFailureAt;
        private int failures;
        private int successes;

        private Tracked(String name) {
            this.name = name;
        }

        private ServerHealth snapshot() {
            return new ServerHealth(status, downSince, upSince, lastCheckAt, lastLatencyMs);
        }
    }
}
