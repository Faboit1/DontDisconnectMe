package top.cheesesmp.ddm.hold;

import com.velocitypowered.api.proxy.Player;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when a reconnect can skip the client's loading screen.
 *
 * <p>The screen comes from the join-game packet the proxy sends when handing a
 * player to a backend: it is what tells the client its entity id changed, and
 * also what makes the client throw its world away. Skip the packet and the
 * world stays on screen - but only if the id really has not changed, or the
 * client ends up talking about an entity the server has never heard of.
 *
 * <p>So this only ever says yes when the companion backend plugin is present
 * and has had a chance to hand the player their previous entity id back. Every
 * other case - no backend plugin, a different server, away too long - falls
 * through to an ordinary reconnect with a loading screen, which is correct if
 * not pretty.
 */
public final class SeamlessCoordinator {

    /** Must match the channel the backend plugin sends on. */
    public static final String CHANNEL = "dontdisconnectme:seamless";

    /** What a backend told us about one player's reconnect. */
    public enum Verdict {
        /** The player kept their previous entity id; skipping was correct. */
        RESTORED,
        /** The player is on a new entity id; the client must be resynced. */
        FRESH
    }

    /** Servers that have announced the backend plugin, and how long they remember ids. */
    private final Map<String, Long> supportedServers = new ConcurrentHashMap<>();
    /** Players we skipped the loading screen for, awaiting the backend's verdict. */
    private final Map<UUID, String> pending = new ConcurrentHashMap<>();

    /**
     * @param serverName the server the player is being returned to
     * @param sameServer whether that is the server they were dropped from
     * @param awayMs     how long they have been held
     * @return true if the join-game packet can safely be skipped
     */
    public boolean canSkipLoadingScreen(String serverName, boolean sameServer, long awayMs,
                                        long maxAwayMs) {
        if (!sameServer) {
            // A different server means a different world; the client has to reload.
            return false;
        }
        Long remembersMs = supportedServers.get(key(serverName));
        if (remembersMs == null) {
            // No backend plugin there, so nobody is handing entity ids back.
            return false;
        }
        long limit = Math.min(maxAwayMs, remembersMs);
        return awayMs < limit;
    }

    public void expectVerdict(UUID playerId, String serverName) {
        pending.put(playerId, key(serverName));
    }

    public boolean isAwaitingVerdict(UUID playerId) {
        return pending.containsKey(playerId);
    }

    public void forget(UUID playerId) {
        pending.remove(playerId);
    }

    public boolean supports(String serverName) {
        return supportedServers.containsKey(key(serverName));
    }

    public Map<String, Long> supportedServers() {
        return Map.copyOf(supportedServers);
    }

    /**
     * Reads one message from a backend.
     *
     * @return the verdict for this player, or null when the message only
     *         announced support and says nothing about the reconnect
     */
    public Verdict handleMessage(Player player, String serverName, byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        int split = payload.indexOf(':');
        String verdict = split < 0 ? payload : payload.substring(0, split);
        String value = split < 0 ? "" : payload.substring(split + 1);

        switch (verdict) {
            case "supported" -> {
                long remembersMs = parseLong(value, 60L) * 1000L;
                supportedServers.put(key(serverName), remembersMs);
                return null;
            }
            case "restored" -> {
                pending.remove(player.getUniqueId());
                return Verdict.RESTORED;
            }
            case "fresh" -> {
                boolean wasPending = pending.remove(player.getUniqueId()) != null;
                // Only a problem if we had already skipped the loading screen.
                return wasPending ? Verdict.FRESH : null;
            }
            default -> {
                return null;
            }
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String key(String serverName) {
        return serverName.toLowerCase(Locale.ROOT);
    }

    public void clear() {
        supportedServers.clear();
        pending.clear();
    }
}
