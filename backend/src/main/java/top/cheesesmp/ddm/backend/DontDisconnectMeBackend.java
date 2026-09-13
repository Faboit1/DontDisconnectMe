package top.cheesesmp.ddm.backend;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The backend half of DontDisconnectMe.
 *
 * <p>On its own the proxy can hold a dropped player on screen, but bringing
 * them back always costs them a "Loading terrain" screen, because the proxy has
 * to send a join-game packet to tell the client its entity id changed - and
 * that packet makes the client throw its world away.
 *
 * <p>This plugin removes the reason for that packet. It remembers the entity id
 * of anyone who drops, hands the same id back if they return within
 * {@code remember-seconds}, and tells the proxy it did. The proxy can then skip
 * the join-game entirely and the player never leaves their world.
 *
 * <p>Nothing here is required: without it the proxy simply reconnects players
 * the ordinary way.
 */
public final class DontDisconnectMeBackend extends JavaPlugin implements Listener {

    /** Must match the channel the proxy plugin listens on. */
    public static final String CHANNEL = "dontdisconnectme:seamless";

    /** Sent on join: this server can hand entity ids back. */
    private static final String SUPPORTED = "supported";
    /** Sent on join: this player kept their previous entity id. */
    private static final String RESTORED = "restored";
    /** Sent on join: this player is on a new entity id, so do not skip anything. */
    private static final String FRESH = "fresh";

    private final Map<UUID, Remembered> remembered = new ConcurrentHashMap<>();
    /** What happened during login, read again when the join event can talk to the proxy. */
    private final Map<UUID, Boolean> restoredAtLogin = new ConcurrentHashMap<>();
    private EntityIdRestorer restorer;
    private long rememberMs;
    private boolean debug;

    /** What we knew about a player the moment they dropped. */
    private record Remembered(int entityId, String world, long expiresAt) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rememberMs = Math.max(1L, getConfig().getLong("remember-seconds", 60L)) * 1000L;
        debug = getConfig().getBoolean("debug", false);

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Ready; entity ids will be handed back for "
                + (rememberMs / 1000L) + "s after a player drops.");
    }

    /**
     * Remember who they were. The entity itself is being removed from the world
     * right now, so the id is free for them to take again.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        prune();
        restoredAtLogin.remove(player.getUniqueId());
        remembered.put(player.getUniqueId(), new Remembered(
                player.getEntityId(),
                player.getWorld().getName(),
                System.currentTimeMillis() + rememberMs));
    }

    /**
     * The id has to be changed here, during login, and not on join.
     *
     * <p>By the time the join event fires the player has already been added to
     * the world and indexed by the chunk system under the id the server gave
     * them. Changing it then leaves the tracker holding a stale index and the
     * server dies on the next tick with "added to world, but was already
     * contained in entity chunk". Doing it during login, before the player is
     * put into the world, means the tracker only ever sees the final id.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }
        Player player = event.getPlayer();
        if (restorer == null) {
            // The first login is all we need to resolve the server internals.
            restorer = new EntityIdRestorer(player);
            if (!restorer.supported()) {
                getLogger().warning("Cannot hand entity ids back on this server build ("
                        + restorer.unsupportedReason() + "); players will reconnect the "
                        + "ordinary way, with a loading screen.");
            }
        }
        restoredAtLogin.remove(player.getUniqueId());
        if (!restorer.supported()) {
            return;
        }

        Remembered previous = remembered.remove(player.getUniqueId());
        if (previous == null || previous.expiresAt() <= System.currentTimeMillis()) {
            return;
        }
        if (!previous.world().equals(player.getWorld().getName())) {
            // A different world may mean a different dimension, and the client
            // would need a respawn regardless. Let it reconnect normally.
            return;
        }

        int assigned = player.getEntityId();
        boolean restored = restorer.restore(player, previous.entityId());
        restoredAtLogin.put(player.getUniqueId(), restored);
        if (debug) {
            getLogger().info("[debug] " + player.getName() + (restored
                    ? " logging in as entity " + assigned + "; handed back " + previous.entityId()
                    : "; could not hand entity " + previous.entityId() + " back"));
        }
    }

    /** Tells the proxy what happened, once the connection can carry a message. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (restorer == null || !restorer.supported()) {
            send(player, FRESH, 0);
            return;
        }
        // How long the proxy can count on us keeping an id, so it never skips
        // the loading screen for somebody who has been away too long. This one
        // waits a moment: a message sent while the proxy is still finishing the
        // handover to this server is dropped before any plugin sees it. It only
        // has to arrive once per server, so a slow announcement costs nothing.
        sendLater(player, SUPPORTED, (int) (rememberMs / 1000L), 40L);

        Boolean restored = restoredAtLogin.remove(player.getUniqueId());
        if (Boolean.TRUE.equals(restored)) {
            send(player, RESTORED, player.getEntityId());
        } else {
            send(player, FRESH, 0);
        }
    }

    /**
     * Tells the proxy what happened, so it only skips the join-game packet when
     * this server actually kept the player's entity id.
     */
    private void send(Player player, String verdict, int value) {
        sendLater(player, verdict, value, 1L);
    }

    private void sendLater(Player player, String verdict, int value, long delayTicks) {
        String payload = verdict + ":" + value;
        // A plugin message sent during the join event itself is dropped - the
        // connection is not ready for it yet - so hand it over on the next tick.
        // The entity scheduler is used because it is the one that works on both
        // Paper and Folia.
        player.getScheduler().runDelayed(this, task -> {
            if (player.isOnline()) {
                player.sendPluginMessage(this, CHANNEL, payload.getBytes(StandardCharsets.UTF_8));
                if (debug) {
                    getLogger().info("[debug] told the proxy: " + payload);
                }
            }
        }, null, delayTicks);
    }

    private void prune() {
        long now = System.currentTimeMillis();
        remembered.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }
}
