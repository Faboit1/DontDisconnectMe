package top.cheesesmp.ddm.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;
import top.cheesesmp.ddm.config.FilterSpec;
import top.cheesesmp.ddm.config.HoldSpec;
import top.cheesesmp.ddm.config.PluginConfig;
import top.cheesesmp.ddm.config.ServerProfile;
import top.cheesesmp.ddm.hold.FreezeHold;
import top.cheesesmp.ddm.hold.HoldServers;
import top.cheesesmp.ddm.reconnect.ReconnectManager;
import top.cheesesmp.ddm.reconnect.ReconnectSession;
import top.cheesesmp.ddm.util.Placeholders;
import top.cheesesmp.ddm.util.Text;
import top.cheesesmp.ddm.watch.ServerWatcher;

/**
 * Intercepts backend kicks and decides whether the plugin takes the player
 * over, and where to park them if it does.
 */
public final class KickListener {

    /**
     * Velocity runs lower priorities first, so this sits late enough that
     * punishment and whitelist plugins have already had their say.
     */
    private static final short LATE = (short) (Short.MAX_VALUE / 2);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final ReconnectManager manager;
    private final ServerWatcher watcher;
    private final FreezeHold freezeHold;

    public KickListener(ProxyServer proxy, Logger logger, Supplier<PluginConfig> config,
                        ReconnectManager manager, ServerWatcher watcher, FreezeHold freezeHold) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.manager = manager;
        this.watcher = watcher;
        this.freezeHold = freezeHold;
    }

    @Subscribe(priority = LATE)
    public void onKickedFromServer(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        PluginConfig current = config.get();
        ServerProfile profile = current.profile(serverName);

        if (!profile.reconnect().enabled()) {
            return;
        }
        FilterSpec filters = profile.filters();
        if (!filters.serverHandled(serverName)) {
            return;
        }
        if (!filters.requirePermission().isEmpty() && !player.hasPermission(filters.requirePermission())) {
            return;
        }

        Component reason = event.getServerKickReason().orElse(null);
        FilterSpec.Decision decision = filters.decide(Text.plain(reason, player.getEffectiveLocale()));
        if (decision == FilterSpec.Decision.PASS_THROUGH) {
            return;
        }
        boolean restartMode = decision == FilterSpec.Decision.RESTART;

        long now = System.currentTimeMillis();
        if (current.watcher().seedDownOnKick()) {
            watcher.seedOffline(serverName, now);
        }
        watcher.markInterest(serverName, now);

        // Preferred path: keep them right where they are, on the proxy, with
        // their client none the wiser. Taking the connection over has to happen
        // now, before Velocity acts on this event's result.
        if (profile.hold().mode() == HoldSpec.Mode.FREEZE && freezeHold.hold(player)) {
            // They are on the proxy itself, so there is no hold server to name.
            manager.start(player, serverName, reason, restartMode, false).holdServer("");
            // Whatever result we set, the disconnect it produces is dropped by
            // the hold. Its text only reaches the console, so make it read
            // sensibly there instead of looking like the player quit.
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    Component.text(profile.hold().consoleReason())));
            return;
        }

        // Kicked mid-transfer: they still have a server, so nothing has to move.
        if (event.kickedDuringServerConnect() && player.getCurrentServer().isPresent()) {
            String currentServer = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse("");
            TagResolver resolver = kickPlaceholders(current, player, serverName, currentServer, reason);
            String notify = profile.hold().notifyMessage();
            event.setResult(KickedFromServerEvent.Notify.create(
                    Text.isBlank(notify) ? Component.empty() : Text.parse(notify, resolver)));
            manager.start(player, serverName, reason, restartMode, false);
            return;
        }

        Optional<RegisteredServer> hold = HoldServers.pick(proxy, watcher, profile.hold(), serverName, now);
        if (hold.isPresent()) {
            String holdName = hold.get().getServerInfo().getName();
            TagResolver resolver = kickPlaceholders(current, player, serverName, holdName, reason);

            // Start the session first so the redirect we are about to trigger is
            // already expected by the time Velocity fires ServerPreConnectEvent.
            ReconnectSession session = manager.start(player, serverName, reason, restartMode, false);
            session.holdServer(holdName);
            session.expectRedirectTo(holdName);

            String redirect = profile.hold().redirectMessage();
            event.setResult(Text.isBlank(redirect)
                    ? KickedFromServerEvent.RedirectPlayer.create(hold.get())
                    : KickedFromServerEvent.RedirectPlayer.create(hold.get(), Text.parse(redirect, resolver)));
            return;
        }

        // Nowhere to put them. Velocity cannot hold a player with no backend.
        logger.warn("No hold server available for {} after being kicked from {} - "
                        + "configure 'hold.servers' with a limbo or lobby server to keep players online.",
                player.getUsername(), serverName);
        if (profile.hold().noServerAction() == HoldSpec.NoServerAction.DISCONNECT) {
            String message = profile.hold().noServerMessage();
            if (!Text.isBlank(message)) {
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(Text.parse(message,
                        kickPlaceholders(current, player, serverName, "", reason))));
            }
        }
    }

    /**
     * The moment a player is on a backend again - whether we put them there or
     * they picked a server themselves - the hold has done its job and must be
     * handed back, or Velocity could never disconnect them again.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        freezeHold.release(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        manager.handleQuit(event.getPlayer().getUniqueId());
    }

    /**
     * A player who picks a different server themselves has opted out - but our
     * own reconnect attempts go through this event too, so only a request for
     * some <em>other</em> server counts as manual.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        ReconnectSession session = manager.session(event.getPlayer().getUniqueId());
        if (session == null || session.connecting()) {
            return;
        }
        if (!session.profile().reconnect().cancelOnManualConnect()) {
            return;
        }
        String requested = event.getOriginalServer().getServerInfo().getName();
        if (session.consumeExpectedRedirect(requested)) {
            // This is the move to the hold server that we asked for.
            return;
        }
        if (!requested.equalsIgnoreCase(session.targetServer())) {
            // They are already on their way somewhere, so just drop the session.
            manager.cancelQuietly(session.playerId());
        }
    }

    private TagResolver kickPlaceholders(PluginConfig current, Player player, String serverName,
                                         String holdServer, Component reason) {
        java.util.Locale locale = player.getEffectiveLocale();
        Component resolved = ReconnectManager.describeReason(reason, current, locale);
        return new Placeholders(current.general().timeFormat())
                .text("player", player.getUsername())
                .text("uuid", player.getUniqueId().toString())
                .text("server", serverName)
                .text("hold_server", holdServer)
                .component("reason", resolved)
                .text("reason_plain", Text.plain(resolved, locale))
                .build();
    }
}
