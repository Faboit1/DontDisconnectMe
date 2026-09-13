package top.cheesesmp.ddm.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import top.cheesesmp.ddm.DontDisconnectMe;
import top.cheesesmp.ddm.config.PluginConfig;
import top.cheesesmp.ddm.reconnect.ReconnectSession;
import top.cheesesmp.ddm.util.Placeholders;
import top.cheesesmp.ddm.util.Text;
import top.cheesesmp.ddm.watch.ServerHealth;

/** {@code /ddm} - reload, inspect and poke the plugin from in game. */
public final class DdmCommand {

    public static final String PERMISSION = "dontdisconnectme.admin";

    private final DontDisconnectMe plugin;
    private final ProxyServer proxy;

    private DdmCommand(DontDisconnectMe plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
    }

    public static BrigadierCommand create(DontDisconnectMe plugin, ProxyServer proxy) {
        DdmCommand handler = new DdmCommand(plugin, proxy);

        LiteralArgumentBuilder<CommandSource> root = LiteralArgumentBuilder
                .<CommandSource>literal("ddm")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(handler::usage);

        root.then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                .executes(handler::reload));

        root.then(LiteralArgumentBuilder.<CommandSource>literal("status")
                .executes(handler::status));

        root.then(LiteralArgumentBuilder.<CommandSource>literal("queue")
                .executes(handler::queueOverview)
                .then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            proxy.getAllServers().forEach(server ->
                                    builder.suggest(server.getServerInfo().getName()));
                            return builder.buildFuture();
                        })
                        .executes(handler::queueFor)));

        root.then(LiteralArgumentBuilder.<CommandSource>literal("cancel")
                .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            plugin.manager().sessions().forEach(session ->
                                    builder.suggest(session.playerName()));
                            return builder.buildFuture();
                        })
                        .executes(handler::cancel)));

        root.then(LiteralArgumentBuilder.<CommandSource>literal("simulate")
                .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            proxy.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
                            return builder.buildFuture();
                        })
                        .executes(context -> handler.simulate(context, null))
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    proxy.getAllServers().forEach(server ->
                                            builder.suggest(server.getServerInfo().getName()));
                                    return builder.buildFuture();
                                })
                                .executes(context -> handler.simulate(context,
                                        StringArgumentType.getString(context, "server"))))));

        return new BrigadierCommand(root);
    }

    // ------------------------------------------------------------------

    private int usage(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        send(source, "<dark_gray>--- <aqua>DontDisconnectMe<dark_gray> ---");
        send(source, "<gray>/ddm reload <dark_gray>- reload config.yml");
        send(source, "<gray>/ddm status <dark_gray>- watched servers and who is waiting");
        send(source, "<gray>/ddm queue [server] <dark_gray>- inspect the re-entry queue");
        send(source, "<gray>/ddm cancel <player> <dark_gray>- stop reconnecting someone");
        send(source, "<gray>/ddm simulate <player> [server] <dark_gray>- preview the screens");
        return 1;
    }

    private int reload(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        long started = System.currentTimeMillis();
        try {
            plugin.reloadConfig();
            long took = System.currentTimeMillis() - started;
            send(source, config().message("reload-success", "<green>Reloaded in <white><ms>ms<green>."),
                    new Placeholders(config().general().timeFormat())
                            .number("ms", took)
                            .build());
        } catch (Exception ex) {
            send(source, config().message("reload-failure", "<red>Reload failed: <white><error>"),
                    new Placeholders(config().general().timeFormat())
                            .text("error", String.valueOf(ex.getMessage()))
                            .build());
            return 0;
        }
        return 1;
    }

    private int status(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        PluginConfig config = config();
        send(source, config.message("status-header", "<dark_gray>--- <aqua>DontDisconnectMe<dark_gray> ---"),
                TagResolver.empty());

        Map<String, ServerHealth> health = plugin.watcher().allHealth();
        if (health.isEmpty()) {
            send(source, config.message("status-none", "<gray> Nothing is being watched right now."),
                    TagResolver.empty());
            return 1;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ServerHealth> entry : health.entrySet()) {
            ServerHealth value = entry.getValue();
            String statusText = switch (value.status()) {
                case ONLINE -> config.message("status-up", "<green>ONLINE");
                case OFFLINE -> config.message("status-down", "<red>OFFLINE");
                case UNKNOWN -> config.message("status-unknown", "<gray>UNKNOWN");
            };
            long since = value.online() ? value.uptimeMs(now) : value.downtimeMs(now);
            send(source, config.message("status-server",
                            "<gray> <white><server><gray>: <status> <dark_gray>(<gray>for <white><downtime><dark_gray>, "
                                    + "<white><waiting><dark_gray> waiting)"),
                    new Placeholders(config.general().timeFormat())
                            .text("server", entry.getKey())
                            .component("status", Text.parse(statusText))
                            .duration("downtime", since)
                            .number("waiting", plugin.manager().countWaitingFor(entry.getKey()))
                            .build());
        }
        return 1;
    }

    private int queueOverview(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        var servers = plugin.manager().queue().servers();
        if (servers.isEmpty()) {
            send(source, config().message("queue-empty", "<gray> The queue is empty."), TagResolver.empty());
            return 1;
        }
        for (String server : servers) {
            printQueue(source, server);
        }
        return 1;
    }

    private int queueFor(CommandContext<CommandSource> context) {
        printQueue(context.getSource(), StringArgumentType.getString(context, "server"));
        return 1;
    }

    private void printQueue(CommandSource source, String server) {
        PluginConfig config = config();
        send(source, config.message("queue-header", "<dark_gray>--- <aqua>Queue for <white><server><dark_gray> ---"),
                new Placeholders(config.general().timeFormat()).text("server", server).build());

        var queued = plugin.manager().queueSnapshot(server);
        if (queued.isEmpty()) {
            send(source, config.message("queue-empty", "<gray> The queue is empty."), TagResolver.empty());
            return;
        }
        long now = System.currentTimeMillis();
        int position = 1;
        for (ReconnectSession session : queued) {
            send(source, config.message("queue-entry",
                            "<gray> <white><queue_position><dark_gray>. <white><player> "
                                    + "<dark_gray>(waiting <gray><elapsed><dark_gray>)"),
                    new Placeholders(config.general().timeFormat())
                            .number("queue_position", position++)
                            .text("player", session.playerName())
                            .duration("elapsed", session.elapsedMs(now))
                            .build());
        }
    }

    private int cancel(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "player");
        PluginConfig config = config();

        Optional<Player> player = proxy.getPlayer(name);
        if (player.isEmpty()) {
            send(source, config.message("player-not-found", "<red>No online player named <white><player><red>."),
                    new Placeholders(config.general().timeFormat()).text("player", name).build());
            return 0;
        }
        if (!plugin.manager().isReconnecting(player.get().getUniqueId())) {
            send(source, config.message("not-reconnecting", "<red><player> is not currently reconnecting."),
                    new Placeholders(config.general().timeFormat()).text("player", name).build());
            return 0;
        }
        plugin.manager().cancel(player.get().getUniqueId());
        send(source, config.message("cancelled", "<yellow>Reconnect cancelled for <white><player><yellow>."),
                new Placeholders(config.general().timeFormat())
                        .text("player", player.get().getUsername())
                        .build());
        return 1;
    }

    private int simulate(CommandContext<CommandSource> context, String serverName) {
        CommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "player");
        PluginConfig config = config();

        Optional<Player> player = proxy.getPlayer(name);
        if (player.isEmpty()) {
            send(source, config.message("player-not-found", "<red>No online player named <white><player><red>."),
                    new Placeholders(config.general().timeFormat()).text("player", name).build());
            return 0;
        }

        String target = serverName != null ? serverName : player.get().getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (target == null || proxy.getServer(target).isEmpty()) {
            send(source, config.message("server-not-found", "<red>No server named <white><server><red>."),
                    new Placeholders(config.general().timeFormat())
                            .text("server", String.valueOf(target))
                            .build());
            return 0;
        }

        plugin.manager().start(player.get(), target,
                Component.text("Simulated kick (/ddm simulate)"), true, true);
        send(source, config.message("simulate-started",
                        "<green>Started a simulated reconnect for <white><player><green> to <white><server><green>."),
                new Placeholders(config.general().timeFormat())
                        .text("player", player.get().getUsername())
                        .text("server", target)
                        .build());
        return 1;
    }

    // ------------------------------------------------------------------

    private PluginConfig config() {
        return plugin.config();
    }

    private void send(CommandSource source, String message) {
        send(source, message, TagResolver.empty());
    }

    private void send(CommandSource source, String message, TagResolver resolver) {
        if (Text.isBlank(message)) {
            return;
        }
        Component prefix = Text.parse(config().prefix());
        source.sendMessage(prefix.append(Text.parse(message, resolver)));
    }
}
