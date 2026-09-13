package top.cheesesmp.ddm;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import top.cheesesmp.ddm.command.DdmCommand;
import top.cheesesmp.ddm.config.ConfigLoader;
import top.cheesesmp.ddm.config.HoldSpec;
import top.cheesesmp.ddm.config.PluginConfig;
import top.cheesesmp.ddm.display.DisplayController;
import top.cheesesmp.ddm.hold.FreezeHold;
import top.cheesesmp.ddm.hold.SeamlessCoordinator;
import top.cheesesmp.ddm.listener.KickListener;
import top.cheesesmp.ddm.queue.ReleaseQueue;
import top.cheesesmp.ddm.reconnect.ReconnectManager;
import top.cheesesmp.ddm.watch.ServerWatcher;

/**
 * Keeps players on the network when a backend server drops them: parks them on
 * a hold server, shows why they were kicked, waits out the restart with a live
 * downtime clock, then trickles everybody back in once it is healthy.
 */
@Plugin(
        id = "dontdisconnectme",
        name = "DontDisconnectMe",
        version = DontDisconnectMe.VERSION,
        description = "Automatically reconnects players when their server restarts or drops them.",
        url = "https://github.com/Faboit1/DontDisconnectMe",
        authors = {"Faboit1"}
)
public final class DontDisconnectMe {

    /** Keep in sync with the version in build.gradle.kts. */
    public static final String VERSION = "1.0.0";

    private final ProxyServer proxy;
    private final Logger logger;
    private final ConfigLoader loader;

    private volatile PluginConfig config;
    private ServerWatcher watcher;
    private ReleaseQueue queue;
    private ReconnectManager manager;
    private DisplayController display;
    private FreezeHold freezeHold;
    private SeamlessCoordinator seamless;
    private ScheduledTask tickTask;
    private long tickIntervalMs;

    @Inject
    public DontDisconnectMe(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.loader = new ConfigLoader(dataDirectory);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = loader.load();
        } catch (IOException ex) {
            logger.error("Could not read config.yml - DontDisconnectMe is disabled for this session.", ex);
            return;
        }
        reportConfigProblems();

        watcher = new ServerWatcher(proxy, config.watcher());
        queue = new ReleaseQueue();
        freezeHold = new FreezeHold();
        seamless = new SeamlessCoordinator();
        MinecraftChannelIdentifier seamlessChannel =
                MinecraftChannelIdentifier.from(SeamlessCoordinator.CHANNEL);
        proxy.getChannelRegistrar().register(seamlessChannel);
        logger.info("Registered channel '{}' for the backend companion plugin.",
                seamlessChannel.getId());
        display = new DisplayController(freezeHold);
        display.debugSink(message -> {
            if (config.general().debug()) {
                logger.info("[debug] {}", message);
            }
        });
        manager = new ReconnectManager(proxy, logger, this::config, watcher, queue, display,
                freezeHold, seamless);
        applyConfigToComponents();

        proxy.getEventManager().register(this,
                new KickListener(proxy, logger, this::config, manager, watcher, freezeHold));

        CommandManager commands = proxy.getCommandManager();
        CommandMeta meta = commands.metaBuilder("ddm")
                .aliases("dontdisconnectme")
                .plugin(this)
                .build();
        commands.register(meta, DdmCommand.create(this, proxy));

        scheduleTicker();
        reportHoldMode();
        logger.info("DontDisconnectMe {} is watching for kicks.", VERSION);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (manager != null) {
            manager.shutdown();
        }
    }

    public PluginConfig config() {
        return config;
    }

    public ReconnectManager manager() {
        return manager;
    }

    public ServerWatcher watcher() {
        return watcher;
    }

    /** Re-reads config.yml and pushes the new settings into every component. */
    public void reloadConfig() throws IOException {
        config = loader.load();
        reportConfigProblems();
        applyConfigToComponents();
        manager.onConfigReloaded();
        scheduleTicker();
        reportHoldMode();
    }

    private void applyConfigToComponents() {
        watcher.configure(config.watcher(), () -> config.general().debug(),
                message -> logger.info("[debug] {}", message));
    }

    private void reportConfigProblems() {
        for (String warning : config.warnings()) {
            logger.warn("config.yml: {}", warning);
        }
        if (config.outdated()) {
            logger.warn("config.yml is version {} but this build ships version {}. "
                            + "Missing options fall back to their defaults; delete the file to regenerate "
                            + "a fully commented one.",
                    config.version(), PluginConfig.CURRENT_VERSION);
        }
    }

    /** Only reschedules when the interval actually changed, so reloads are cheap. */
    private void scheduleTicker() {
        long interval = config.general().tickIntervalMs();
        if (tickTask != null && interval == tickIntervalMs) {
            return;
        }
        if (tickTask != null) {
            tickTask.cancel();
        }
        tickIntervalMs = interval;
        tickTask = proxy.getScheduler()
                .buildTask(this, () -> manager.tick())
                .repeat(interval, TimeUnit.MILLISECONDS)
                .schedule();
    }

    /**
     * Says once, at startup and on reload, how players will actually be held -
     * including when FREEZE was asked for but this Velocity build cannot do it.
     */
    private void reportHoldMode() {
        HoldSpec hold = config.defaultProfile().hold();
        boolean anyHoldServer = hold.servers().stream()
                .anyMatch(name -> proxy.getServer(name).isPresent());

        if (hold.mode() == HoldSpec.Mode.FREEZE) {
            if (freezeHold.supported()) {
                logger.info("Players will be held on the proxy itself; no limbo server needed.");
                return;
            }
            logger.warn("Cannot hold players on the proxy on this Velocity build ({}). "
                            + "Falling back to moving them to a hold server.",
                    freezeHold.unsupportedReason());
        }
        if (anyHoldServer) {
            logger.info("Players will be moved to a hold server while their own server is away.");
            return;
        }
        logger.warn("None of the servers in 'hold.servers' exist in velocity.toml, so players kicked from "
                + "their only server will be disconnected. Set 'hold.mode: FREEZE' (recommended) or add a "
                + "limbo or lobby server to 'hold.servers'.");
    }
}
