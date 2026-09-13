package top.cheesesmp.ddm.hold;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import top.cheesesmp.ddm.config.HoldSpec;
import top.cheesesmp.ddm.watch.ServerWatcher;

/** Picks the hold server a player should be parked on. */
public final class HoldServers {

    private HoldServers() {
    }

    /**
     * First configured hold server that exists, is not the one that just died,
     * and is not known to be offline.
     *
     * @param exclude the server the player was kicked from, skipped if listed
     */
    public static Optional<RegisteredServer> pick(ProxyServer proxy, ServerWatcher watcher,
                                                  HoldSpec hold, String exclude, long now) {
        for (String candidate : hold.servers()) {
            if (candidate.equalsIgnoreCase(exclude)) {
                continue;
            }
            Optional<RegisteredServer> server = proxy.getServer(candidate);
            if (server.isEmpty()) {
                continue;
            }
            String name = server.get().getServerInfo().getName();
            watcher.markInterest(name, now);
            if (hold.onlyOnline() && watcher.health(name).offline()) {
                continue;
            }
            return server;
        }
        return Optional.empty();
    }
}
