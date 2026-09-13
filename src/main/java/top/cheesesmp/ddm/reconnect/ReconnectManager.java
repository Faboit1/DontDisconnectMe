package top.cheesesmp.ddm.reconnect;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import top.cheesesmp.ddm.config.Phase;
import top.cheesesmp.ddm.config.PhaseSpec;
import top.cheesesmp.ddm.config.PluginConfig;
import top.cheesesmp.ddm.config.QueueSpec;
import top.cheesesmp.ddm.config.ReconnectSpec;
import top.cheesesmp.ddm.config.ServerProfile;
import top.cheesesmp.ddm.config.HoldSpec;
import top.cheesesmp.ddm.display.DisplayController;
import top.cheesesmp.ddm.hold.FreezeHold;
import top.cheesesmp.ddm.hold.HoldServers;
import top.cheesesmp.ddm.queue.ReleaseQueue;
import top.cheesesmp.ddm.util.Placeholders;
import top.cheesesmp.ddm.util.Text;
import top.cheesesmp.ddm.watch.ServerHealth;
import top.cheesesmp.ddm.watch.ServerWatcher;

/**
 * Owns every in-flight reconnect and drives them all from a single repeating
 * task, so there is exactly one clock in the plugin and no per-player timers.
 */
public final class ReconnectManager {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final ServerWatcher watcher;
    private final ReleaseQueue queue;
    private final DisplayController display;
    private final FreezeHold freezeHold;

    private final Map<UUID, ReconnectSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Memory> memories = new ConcurrentHashMap<>();

    /**
     * What we remember about a session that just ended, so a player kicked again
     * seconds later (a server still finishing its boot) resumes instead of
     * restarting their timer from zero.
     */
    private record Memory(String targetServer, int attempts, long startedAt,
                          boolean restartMode, long expiresAt) {
    }

    public ReconnectManager(ProxyServer proxy, Logger logger, Supplier<PluginConfig> config,
                            ServerWatcher watcher, ReleaseQueue queue, DisplayController display,
                            FreezeHold freezeHold) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.watcher = watcher;
        this.queue = queue;
        this.display = display;
        this.freezeHold = freezeHold;
    }

    /**
     * A crashed or timed-out backend gives Velocity no kick reason at all, which
     * would leave "<reason>" blank on screen. Fall back to a configured phrase so
     * the player is still told something useful.
     */
    public static Component describeReason(Component reason, PluginConfig config, Locale locale) {
        if (reason != null && !Text.plain(reason, locale).isBlank()) {
            return reason;
        }
        return Text.parse(config.message("unknown-reason", "Connection lost"));
    }

    public Collection<ReconnectSession> sessions() {
        return sessions.values();
    }

    public ReconnectSession session(UUID playerId) {
        return sessions.get(playerId);
    }

    public boolean isReconnecting(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public int countWaitingFor(String serverName) {
        int count = 0;
        for (ReconnectSession session : sessions.values()) {
            if (session.targetServer().equalsIgnoreCase(serverName)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /**
     * Begins (or resumes) a reconnect for a player who has just been kicked.
     *
     * @param restartMode true when the kick reason says the server is going
     *                    down, which skips the instant retry
     */
    public ReconnectSession start(Player player, String targetServer, Component reason,
                                  boolean restartMode, boolean simulated) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();

        ReconnectSession existing = sessions.get(id);
        if (existing != null) {
            endSession(existing, player, false);
        }

        ServerProfile profile = config.get().profile(targetServer);
        Memory memory = memories.remove(id);
        boolean resumable = memory != null
                && memory.expiresAt() > now
                && memory.targetServer().equalsIgnoreCase(targetServer);

        Locale locale = player.getEffectiveLocale();
        Component describedReason = describeReason(reason, config.get(), locale);
        ReconnectSession session = new ReconnectSession(
                id,
                player.getUsername(),
                targetServer,
                describedReason,
                Text.plain(describedReason, locale),
                profile,
                restartMode || (resumable && memory.restartMode()),
                resumable ? memory.startedAt() : now,
                now,
                simulated);
        if (resumable) {
            session.seedAttempts(memory.attempts());
        }
        player.getCurrentServer().ifPresent(connection ->
                session.holdServer(connection.getServerInfo().getName()));
        session.scheduleAttempt(now, profile.reconnect().immediate().delayMs());

        sessions.put(id, session);
        debug(() -> "started session for " + player.getUsername() + " -> " + targetServer
                + (resumable ? " (resumed, " + memory.attempts() + " prior attempts)" : "")
                + (restartMode ? " [restart mode]" : ""));
        return session;
    }

    /** The player left the proxy; keep a short memory in case they return. */
    public void handleQuit(UUID playerId) {
        ReconnectSession session = sessions.get(playerId);
        if (session != null) {
            endSession(session, null, true);
        }
    }

    /**
     * Ends a session without standing the player down, for when something else
     * is already moving them - a manual {@code /server}, for instance. The hold
     * is handed back as soon as they land.
     */
    public void cancelQuietly(UUID playerId) {
        ReconnectSession session = sessions.get(playerId);
        if (session != null) {
            endSession(session, proxy.getPlayer(playerId).orElse(null), false);
        }
    }

    /** Called when an admin cancels, or a session is abandoned. */
    public void cancel(UUID playerId) {
        ReconnectSession session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        Player player = proxy.getPlayer(playerId).orElse(null);
        endSession(session, player, false);
        // They asked to stop, so do not leave them held with nothing to do.
        standDown(session, player, false, Component.empty());
    }

    private void endSession(ReconnectSession session, Player player, boolean remember) {
        sessions.remove(session.playerId());
        queue.remove(session.playerId());
        // Only hand the connection back to Velocity once the player is somewhere
        // it can disconnect them from. Releasing a held player who is attached to
        // nothing would stop their keep-alives and time them out.
        if (player == null || player.getCurrentServer().isPresent()) {
            freezeHold.release(session.playerId());
        }
        if (player != null) {
            display.clearAll(player, session, session.profile().reconnect().extraStopKeys());
        }
        if (remember) {
            ReconnectSpec spec = session.profile().reconnect();
            if (spec.sessionMemoryMs() > 0L) {
                memories.put(session.playerId(), new Memory(
                        session.targetServer(),
                        session.attempts(),
                        session.startedAt(),
                        session.restartMode(),
                        System.currentTimeMillis() + spec.sessionMemoryMs()));
            }
        }
    }

    public void shutdown() {
        for (ReconnectSession session : List.copyOf(sessions.values())) {
            endSession(session, proxy.getPlayer(session.playerId()).orElse(null), false);
        }
        freezeHold.releaseAll();
        queue.clear();
        memories.clear();
    }

    /** Re-resolves every live session against a freshly loaded config. */
    public void onConfigReloaded() {
        PluginConfig current = config.get();
        for (ReconnectSession session : sessions.values()) {
            session.profile(current.profile(session.targetServer()));
        }
    }

    // ------------------------------------------------------------------
    //  The clock
    // ------------------------------------------------------------------

    public void tick() {
        long now = System.currentTimeMillis();
        watcher.tick(now);
        freezeHold.tickKeepAlive(now, config.get().defaultProfile().hold().keepAliveIntervalMs());

        List<ReconnectSession> live = List.copyOf(sessions.values());
        for (ReconnectSession session : live) {
            try {
                advanceSession(session, now);
            } catch (RuntimeException ex) {
                logger.warn("Error while ticking reconnect session for {}", session.playerName(), ex);
            }
        }
        releaseQueues(now);

        // Read every queue position only after the whole tick has settled,
        // otherwise the first player to be queued is told the queue holds one
        // person and the last is told it holds five.
        for (ReconnectSession session : live) {
            if (sessions.containsKey(session.playerId()) && session.queued()) {
                refreshQueueState(session);
            }
        }

        // Render only once every session has settled, so that the queue sizes
        // and positions everyone is shown in a tick agree with each other.
        for (ReconnectSession session : live) {
            if (!sessions.containsKey(session.playerId())) {
                continue;
            }
            try {
                proxy.getPlayer(session.playerId()).ifPresent(player ->
                        display.update(player, session, placeholders(session, now), now));
            } catch (RuntimeException ex) {
                logger.warn("Error while updating the display for {}", session.playerName(), ex);
            }
        }
        pruneMemories(now);
    }

    private void advanceSession(ReconnectSession session, long now) {
        Optional<Player> online = proxy.getPlayer(session.playerId());
        if (online.isEmpty()) {
            // They gave up and left; remember it briefly in case they come back.
            endSession(session, null, true);
            return;
        }
        Player player = online.get();
        session.profile(config.get().profile(session.targetServer()));

        if (session.simulated()) {
            tickPreview(session, player, now);
            return;
        }
        if (!session.phase().terminal() && isOnTarget(player, session)) {
            session.transition(Phase.SUCCESS, now);
        }

        switch (session.phase()) {
            case KICKED -> tickKicked(session, player, now);
            case WAITING -> tickWaiting(session, player, now);
            case RECONNECTING -> tickReconnecting(session, player, now);
            case SUCCESS, FAILED -> tickTerminal(session, player, now);
        }
    }

    /**
     * {@code /ddm simulate} walks the player through every screen on a fixed
     * script. Nothing is pinged, queued or connected.
     */
    private void tickPreview(ReconnectSession session, Player player, long now) {
        Phase wanted = config.get().simulate().phaseAt(session.elapsedMs(now));
        session.transition(wanted, now);
        session.queueState(1, 1, 0L);
        if (wanted.terminal()) {
            tickTerminal(session, player, now);
        } else if (now >= session.nextAttemptAt()) {
            // Keep the retry countdown ticking so the preview looks real.
            session.scheduleAttempt(now, session.profile().reconnect().retry().intervalMs());
        }
    }

    private void tickKicked(ReconnectSession session, Player player, long now) {
        ReconnectSpec.Immediate immediate = session.profile().reconnect().immediate();
        boolean instantRetryLeft = immediate.enabled()
                && !session.restartMode()
                && session.immediateAttempts() < immediate.maxAttempts();

        if (instantRetryLeft) {
            if (!session.connecting() && now >= session.nextAttemptAt()) {
                attempt(session, player, now);
            }
            return;
        }
        if (session.connecting()) {
            return;
        }
        PhaseSpec spec = session.spec();
        if (session.phaseElapsedMs(now) >= spec.durationMs()) {
            leaveKickedPhase(session, now);
        }
    }

    private void leaveKickedPhase(ReconnectSession session, long now) {
        if (watcher.isOnline(session.targetServer()) && !session.restartMode()) {
            enterReconnecting(session, now);
        } else {
            enterWaiting(session, now);
        }
    }

    private void enterWaiting(ReconnectSession session, long now) {
        if (session.queued()) {
            queue.remove(session.playerId());
            session.queued(false);
        }
        if (session.transition(Phase.WAITING, now)) {
            session.queueState(0, 0, 0L);
            session.scheduleAttempt(now, session.profile().reconnect().retry()
                    .delayForAttempt(session.attempts() + 1));
            debug(() -> session.playerName() + " is waiting for " + session.targetServer());
        }
    }

    private void tickWaiting(ReconnectSession session, Player player, long now) {
        watcher.markInterest(session.targetServer(), now);
        if (checkGiveUp(session, now)) {
            return;
        }

        if (watcher.isOnline(session.targetServer())) {
            enterReconnecting(session, now);
            return;
        }
        if (session.connecting() || now < session.nextAttemptAt()) {
            return;
        }

        // The retry cadence keeps running even when we are only pinging, so the
        // countdown the player sees always matches what the plugin is doing.
        display.playRetrySound(player, session.spec());
        ReconnectSpec.Retry retry = session.profile().reconnect().retry();
        if (retry.onlyWhenOnline()) {
            session.scheduleAttempt(now, retry.delayForAttempt(session.attempts() + 1));
        } else {
            attempt(session, player, now);
        }
    }

    private void enterReconnecting(ReconnectSession session, long now) {
        if (!session.transition(Phase.RECONNECTING, now)) {
            return;
        }
        QueueSpec queueSpec = session.profile().queue();
        Player player = proxy.getPlayer(session.playerId()).orElse(null);
        boolean bypass = player != null && !queueSpec.bypassPermission().isEmpty()
                && player.hasPermission(queueSpec.bypassPermission());

        if (!queueSpec.enabled() || bypass) {
            session.queued(false);
            // No queue to report, but "1 of 1" reads better than "0 of 0".
            session.queueState(1, 1, 0L);
            session.scheduleAttempt(now, 0L);
        } else {
            queue.enqueue(session.targetServer(), session.playerId(),
                    priorityOf(player, queueSpec), session.startedAt());
            session.queued(true);
        }
        debug(() -> session.playerName() + " is queued for " + session.targetServer()
                + (session.queued() ? "" : " (bypassing the queue)"));
    }

    private void tickReconnecting(ReconnectSession session, Player player, long now) {
        watcher.markInterest(session.targetServer(), now);
        if (checkGiveUp(session, now)) {
            return;
        }

        ServerHealth health = watcher.health(session.targetServer());
        if (health.offline()) {
            // It went away again mid-queue - back to the restart screen.
            debug(() -> session.targetServer() + " went down again, " + session.playerName()
                    + " returns to waiting");
            enterWaiting(session, now);
            return;
        }

        if (session.queued()) {
            if (queue.position(session.targetServer(), session.playerId()) == 0) {
                // Dropped out of the queue somehow - put them back.
                queue.enqueue(session.targetServer(), session.playerId(),
                        priorityOf(player, session.profile().queue()), session.startedAt());
            }
            return;
        }

        session.queueState(1, 1, 0L);
        if (!session.connecting() && now >= session.nextAttemptAt()) {
            attempt(session, player, now);
        }
    }

    private void refreshQueueState(ReconnectSession session) {
        int position = queue.position(session.targetServer(), session.playerId());
        int size = queue.size(session.targetServer());
        session.queueState(position, size, session.profile().queue().etaMsFor(position));
    }

    private void tickTerminal(ReconnectSession session, Player player, long now) {
        if (session.endAt() == 0L) {
            ReconnectSpec spec = session.profile().reconnect();
            if (session.phase() != Phase.SUCCESS || spec.stopMusicOnSuccess()) {
                display.stopSounds(player, session, spec.extraStopKeys());
            }
            display.clearBossBar(player, session);
            queue.remove(session.playerId());
            session.queued(false);
            session.endAt(now + Math.max(250L, session.spec().durationMs()));
        }
        if (now >= session.endAt()) {
            finishTerminal(session, player);
        }
    }

    private void finishTerminal(ReconnectSession session, Player player) {
        boolean failed = session.phase() == Phase.FAILED;
        ReconnectSpec spec = session.profile().reconnect();
        Component giveUpMessage = Text.isBlank(spec.retry().giveUpMessage())
                ? Component.text("Could not reconnect you.")
                : Text.parse(spec.retry().giveUpMessage(),
                        placeholders(session, System.currentTimeMillis()));
        endSession(session, player, true);
        player.clearTitle();
        player.sendActionBar(Component.empty());

        boolean disconnect = failed
                && spec.retry().onGiveUp() == ReconnectSpec.GiveUpAction.DISCONNECT;
        standDown(session, player, disconnect, giveUpMessage);
    }

    /**
     * Stops holding a player without stranding them. A held player is attached
     * to no server at all, so simply releasing them would end their keep-alives
     * and time them out; they are moved to a hold server instead, and only kept
     * held when there is nowhere at all to put them.
     */
    private void standDown(ReconnectSession session, Player player, boolean disconnect, Component message) {
        UUID playerId = session.playerId();
        if (player == null || !freezeHold.isHeld(playerId)) {
            freezeHold.release(playerId);
            return;
        }
        if (disconnect) {
            freezeHold.release(playerId);
            player.disconnect(message);
            return;
        }
        Optional<RegisteredServer> fallback = HoldServers.pick(proxy, watcher,
                session.profile().hold(), session.targetServer(), System.currentTimeMillis());
        if (fallback.isEmpty()) {
            // Nowhere to go. Keep holding them - they stay online and can pick a
            // server themselves - rather than dropping them into a timeout.
            debug(() -> "keeping " + session.playerName() + " held; no hold server to stand down to");
            return;
        }
        player.createConnectionRequest(fallback.get()).connect().whenComplete((result, error) -> {
            if (error == null && result != null && result.isSuccessful()) {
                freezeHold.release(playerId);
            }
        });
    }

    private boolean checkGiveUp(ReconnectSession session, long now) {
        ReconnectSpec.Retry retry = session.profile().reconnect().retry();
        if (retry.durationExhausted(session.elapsedMs(now))
                || retry.attemptsExhausted(session.attempts())) {
            debug(() -> "giving up on " + session.playerName() + " -> " + session.targetServer());
            if (session.queued()) {
                queue.remove(session.playerId());
                session.queued(false);
            }
            session.transition(Phase.FAILED, now);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Connection attempts
    // ------------------------------------------------------------------

    private void attempt(ReconnectSession session, Player player, long now) {
        ReconnectSpec.Retry retry = session.profile().reconnect().retry();
        Optional<RegisteredServer> target = proxy.getServer(session.targetServer());
        if (target.isEmpty()) {
            logger.warn("Server '{}' is no longer registered; giving up on {}",
                    session.targetServer(), session.playerName());
            session.transition(Phase.FAILED, now);
            return;
        }
        if (player.getCurrentServer().isEmpty() && !freezeHold.isHeld(session.playerId())) {
            // Still mid-handshake from the kick redirect - try again shortly
            // rather than burning an attempt on a guaranteed failure. A held
            // player legitimately has no server, so this never applies to them.
            session.scheduleAttempt(now, Math.min(500L, retry.minIntervalMs()));
            return;
        }

        session.connecting(true);
        session.recordAttempt(now);
        debug(() -> "attempt #" + session.attempts() + " for " + session.playerName()
                + " -> " + session.targetServer());

        if (session.simulated()) {
            // /ddm simulate never actually moves anybody.
            session.connecting(false);
            session.scheduleAttempt(System.currentTimeMillis(),
                    retry.delayForAttempt(session.attempts() + 1));
            return;
        }

        player.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            session.connecting(false);
            long finishedAt = System.currentTimeMillis();
            if (error != null) {
                onAttemptFailed(session, finishedAt, true);
                return;
            }
            ConnectionRequestBuilder.Status status = result.getStatus();
            if (status == ConnectionRequestBuilder.Status.SUCCESS
                    || status == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                debug(() -> session.playerName() + " is back on " + session.targetServer());
                session.transition(Phase.SUCCESS, finishedAt);
                return;
            }
            if (status == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS) {
                // Not a real failure - do not hold it against the player.
                session.scheduleAttempt(finishedAt, Math.min(500L, retry.minIntervalMs()));
                return;
            }
            onAttemptFailed(session, finishedAt,
                    status == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED);
        });
    }

    private void onAttemptFailed(ReconnectSession session, long now, boolean serverLikelyDown) {
        if (serverLikelyDown && config.get().watcher().seedDownOnKick()) {
            watcher.seedOffline(session.targetServer(), now);
        }
        ReconnectSpec spec = session.profile().reconnect();

        if (session.phase() == Phase.KICKED) {
            if (session.immediateAttempts() >= spec.immediate().maxAttempts()) {
                leaveKickedPhase(session, now);
            } else {
                session.scheduleAttempt(now, spec.immediate().delayMs());
            }
            return;
        }

        if (session.phase() == Phase.RECONNECTING && session.profile().queue().enabled()) {
            // Keep the original queue timestamp so a failed attempt does not
            // send the player to the back of the line.
            queue.enqueue(session.targetServer(), session.playerId(),
                    priorityOf(proxy.getPlayer(session.playerId()).orElse(null),
                            session.profile().queue()),
                    session.startedAt());
            session.queued(true);
        }
        session.scheduleAttempt(now, spec.retry().delayForAttempt(session.attempts() + 1));
    }

    private void releaseQueues(long now) {
        for (String serverName : queue.servers()) {
            if (queue.size(serverName) == 0) {
                continue;
            }
            QueueSpec spec = config.get().profile(serverName).queue();
            ServerHealth health = watcher.health(serverName);
            if (!health.online() || health.uptimeMs(now) < spec.startDelayMs()) {
                // Not healthy (or not healthy for long enough) - hold the line.
                queue.resetReleaseClock(serverName);
                continue;
            }
            for (UUID playerId : queue.release(serverName, spec.batchSize(), spec.intervalMs(), now)) {
                ReconnectSession session = sessions.get(playerId);
                if (session == null) {
                    continue;
                }
                session.queued(false);
                proxy.getPlayer(playerId).ifPresentOrElse(
                        player -> attempt(session, player, now),
                        () -> endSession(session, null, true));
            }
        }
    }

    private void pruneMemories(long now) {
        memories.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private boolean isOnTarget(Player player, ReconnectSession session) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()
                        .equalsIgnoreCase(session.targetServer()))
                .orElse(false);
    }

    private int priorityOf(Player player, QueueSpec spec) {
        if (player == null) {
            return ReleaseQueue.NO_PRIORITY;
        }
        List<String> permissions = spec.priorityPermissions();
        for (int i = 0; i < permissions.size(); i++) {
            if (player.hasPermission(permissions.get(i))) {
                return i;
            }
        }
        return ReleaseQueue.NO_PRIORITY;
    }

    /** Builds the placeholder set every message for this session can use. */
    public TagResolver placeholders(ReconnectSession session, long now) {
        PluginConfig current = config.get();
        ServerHealth health = watcher.health(session.targetServer());
        long downtime = health.downtimeMs(now);
        if (downtime == 0L && (session.simulated() || !health.online())) {
            downtime = session.elapsedMs(now);
        }
        int maxAttempts = session.profile().reconnect().retry().maxAttempts();
        long untilRetry = Math.max(0L, session.nextAttemptAt() - now);

        return new Placeholders(current.general().timeFormat())
                .text("player", session.playerName())
                .text("uuid", session.playerId().toString())
                .text("server", session.targetServer())
                .text("hold_server", session.holdServer())
                .component("reason", session.reason())
                .text("reason_plain", session.reasonPlain())
                .text("phase", session.phase().configKey())
                .number("attempt", session.attempts())
                .text("max_attempts", maxAttempts == 0 ? "unlimited" : Integer.toString(maxAttempts))
                .duration("downtime", downtime)
                .duration("elapsed", session.elapsedMs(now))
                .number("next_retry", (untilRetry + 999L) / 1000L)
                .number("queue_position", session.queuePosition())
                .number("queue_size", session.queueSize())
                .duration("queue_eta", session.queueEtaMs())
                .build();
    }

    /** Sorted view of a server's queue, for {@code /ddm queue}. */
    public List<ReconnectSession> queueSnapshot(String serverName) {
        List<ReconnectSession> out = new ArrayList<>();
        for (ReleaseQueue.Entry entry : queue.view(serverName)) {
            ReconnectSession session = sessions.get(entry.player());
            if (session != null) {
                out.add(session);
            }
        }
        return out;
    }

    public ReleaseQueue queue() {
        return queue;
    }

    public ServerWatcher watcher() {
        return watcher;
    }

    private void debug(Supplier<String> message) {
        if (config.get().general().debug()) {
            logger.info("[debug] {}", message.get());
        }
    }

    /** Case-insensitive lookup used by the command handlers. */
    public Optional<String> resolveServerName(String input) {
        return proxy.getAllServers().stream()
                .map(server -> server.getServerInfo().getName())
                .filter(name -> name.toLowerCase(Locale.ROOT).equals(input.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
