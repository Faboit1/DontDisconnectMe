package top.cheesesmp.ddm.reconnect;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import top.cheesesmp.ddm.config.Phase;
import top.cheesesmp.ddm.config.PhaseSpec;
import top.cheesesmp.ddm.config.ServerProfile;

/**
 * One player's journey back to their server.
 *
 * <p>Mutated only from the plugin's scheduler thread and from connection
 * callbacks, so the fields that both touch are volatile and the phase
 * transitions are synchronized.
 */
public final class ReconnectSession {

    private final UUID playerId;
    private final String playerName;
    private final String targetServer;
    private final Component reason;
    private final String reasonPlain;
    private final long startedAt;
    private final boolean simulated;

    private volatile ServerProfile profile;
    private volatile String holdServer = "";
    private volatile String pendingRedirect = "";
    private volatile Phase phase = Phase.KICKED;
    private volatile long phaseStartedAt;
    private volatile int attempts;
    private volatile int immediateAttempts;
    private volatile long nextAttemptAt;
    private volatile long lastAttemptAt;
    private volatile long retryAnchorAt;
    private volatile long nextChatAt;
    private volatile long nextDisplayAt;
    private volatile long endAt;
    private volatile boolean connecting;
    private volatile boolean restartMode;
    private volatile boolean queued;
    private volatile int queuePosition;
    private volatile int queueSize;
    private volatile long queueEtaMs;
    private volatile BossBar bossBar;

    /** Set when a phase starts, cleared by the display once it has set it up. */
    private volatile boolean phaseEntered = true;
    /** Next play time per sound of the current phase; aligned with its sound list. */
    private volatile long[] soundSchedule = new long[0];
    /** Sound keys started by this session that must be stopped again later. */
    private final Set<Key> stoppableSounds = new LinkedHashSet<>();

    /**
     * @param startedAt when the player's ordeal began - carried over from a
     *                  resumed session so the "away for" clock stays honest
     * @param now       the current time, used to start the first phase
     */
    public ReconnectSession(UUID playerId, String playerName, String targetServer,
                            Component reason, String reasonPlain, ServerProfile profile,
                            boolean restartMode, long startedAt, long now, boolean simulated) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.targetServer = targetServer;
        this.reason = reason;
        this.reasonPlain = reasonPlain;
        this.profile = profile;
        this.restartMode = restartMode;
        this.startedAt = startedAt;
        this.phaseStartedAt = now;
        this.simulated = simulated;
        // The opening phase never goes through transition(), so it needs its
        // sound schedule laid out here or it would start silent.
        scheduleSoundsFor(profile.phase(phase), now);
    }

    private void scheduleSoundsFor(PhaseSpec spec, long now) {
        int soundCount = spec == null ? 0 : spec.sounds().size();
        long[] schedule = new long[soundCount];
        for (int i = 0; i < soundCount; i++) {
            schedule[i] = now + spec.sounds().get(i).delayMs();
        }
        soundSchedule = schedule;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public String targetServer() {
        return targetServer;
    }

    public Component reason() {
        return reason;
    }

    public String reasonPlain() {
        return reasonPlain;
    }

    public long startedAt() {
        return startedAt;
    }

    public long elapsedMs(long now) {
        return Math.max(0L, now - startedAt);
    }

    public boolean simulated() {
        return simulated;
    }

    public ServerProfile profile() {
        return profile;
    }

    public void profile(ServerProfile profile) {
        this.profile = profile;
    }

    public PhaseSpec spec() {
        return profile.phase(phase);
    }

    public Phase phase() {
        return phase;
    }

    public long phaseStartedAt() {
        return phaseStartedAt;
    }

    public long phaseElapsedMs(long now) {
        return Math.max(0L, now - phaseStartedAt);
    }

    public String holdServer() {
        return holdServer;
    }

    public void holdServer(String holdServer) {
        this.holdServer = holdServer == null ? "" : holdServer;
    }

    /**
     * Records that we are about to move the player to {@code server} ourselves,
     * so the "they picked a server manually" guard does not mistake our own
     * hold-server redirect for the player opting out.
     */
    public void expectRedirectTo(String server) {
        this.pendingRedirect = server == null ? "" : server;
    }

    /** @return true if {@code server} is the move we were expecting */
    public boolean consumeExpectedRedirect(String server) {
        if (!pendingRedirect.isEmpty() && pendingRedirect.equalsIgnoreCase(server)) {
            pendingRedirect = "";
            return true;
        }
        return false;
    }

    public int attempts() {
        return attempts;
    }

    public int immediateAttempts() {
        return immediateAttempts;
    }

    public long nextAttemptAt() {
        return nextAttemptAt;
    }

    /** Schedules the next attempt and remembers when the countdown started. */
    public void scheduleAttempt(long now, long delayMs) {
        this.retryAnchorAt = now;
        this.nextAttemptAt = now + delayMs;
    }

    /** Length of the current retry countdown, for progress bars. */
    public long retryWindowMs() {
        return Math.max(1L, nextAttemptAt - retryAnchorAt);
    }

    public long lastAttemptAt() {
        return lastAttemptAt;
    }

    public boolean connecting() {
        return connecting;
    }

    public void connecting(boolean connecting) {
        this.connecting = connecting;
    }

    public boolean restartMode() {
        return restartMode;
    }

    public void restartMode(boolean restartMode) {
        this.restartMode = restartMode;
    }

    public boolean queued() {
        return queued;
    }

    public void queued(boolean queued) {
        this.queued = queued;
    }

    public int queuePosition() {
        return queuePosition;
    }

    public int queueSize() {
        return queueSize;
    }

    public long queueEtaMs() {
        return queueEtaMs;
    }

    public void queueState(int position, int size, long etaMs) {
        this.queuePosition = position;
        this.queueSize = size;
        this.queueEtaMs = etaMs;
    }

    public BossBar bossBar() {
        return bossBar;
    }

    public void bossBar(BossBar bossBar) {
        this.bossBar = bossBar;
    }

    /** When a terminal phase should be torn down; 0 while the session is live. */
    public long endAt() {
        return endAt;
    }

    public void endAt(long endAt) {
        this.endAt = endAt;
    }

    public long nextChatAt() {
        return nextChatAt;
    }

    public void nextChatAt(long at) {
        this.nextChatAt = at;
    }

    public long nextDisplayAt() {
        return nextDisplayAt;
    }

    public void nextDisplayAt(long at) {
        this.nextDisplayAt = at;
    }

    /** Carries the attempt count of a resumed session forward. */
    public void seedAttempts(int previousAttempts) {
        this.attempts += Math.max(0, previousAttempts);
    }

    public void recordAttempt(long now) {
        attempts++;
        lastAttemptAt = now;
        if (phase == Phase.KICKED) {
            immediateAttempts++;
        }
    }

    /**
     * Moves to a new phase and resets everything the display layer keys off.
     *
     * @return false when the session was already in that phase
     */
    public synchronized boolean transition(Phase next, long now) {
        if (phase == next) {
            return false;
        }
        phase = next;
        phaseStartedAt = now;
        phaseEntered = true;
        nextChatAt = now;
        nextDisplayAt = now;
        scheduleSoundsFor(profile.phase(next), now);
        return true;
    }

    /**
     * @return true exactly once per phase, for the first display update of it
     */
    public boolean consumePhaseEntered() {
        if (!phaseEntered) {
            return false;
        }
        phaseEntered = false;
        return true;
    }

    public long[] soundSchedule() {
        return soundSchedule;
    }

    public void soundScheduleAt(int index, long at) {
        long[] schedule = soundSchedule;
        if (index >= 0 && index < schedule.length) {
            schedule[index] = at;
        }
    }

    public synchronized void rememberStoppable(Key key) {
        stoppableSounds.add(key);
    }

    public synchronized Set<Key> drainStoppableSounds() {
        Set<Key> copy = Set.copyOf(stoppableSounds);
        stoppableSounds.clear();
        return copy;
    }

    public synchronized Set<Key> stoppableSounds() {
        return Set.copyOf(stoppableSounds);
    }

    @Override
    public String toString() {
        return "ReconnectSession{" + playerName + " -> " + targetServer
                + ", phase=" + phase + ", attempts=" + attempts
                + ", sounds=" + Arrays.toString(soundSchedule) + '}';
    }
}
