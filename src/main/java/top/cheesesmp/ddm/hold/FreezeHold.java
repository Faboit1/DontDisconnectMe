package top.cheesesmp.ddm.hold;

import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandlerContext;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;

/**
 * Holds a player on the proxy itself after their backend server dies, instead
 * of moving them to a limbo server.
 *
 * <p>The client is never told anything happened: no disconnect packet is sent
 * and no new server sends it a join/respawn, so it keeps rendering the world it
 * already has. The player can look around but nothing they do is acknowledged,
 * because there is no longer a server behind the proxy to acknowledge it. When
 * the real server returns they are simply connected to it, which is the first
 * respawn their client sees.
 *
 * <p>Velocity's public API cannot express "keep this player attached to nothing",
 * so this is the one part of the plugin that reaches into proxy internals:
 *
 * <ul>
 *   <li>a netty outbound handler on the player's channel drops the disconnect
 *       packet Velocity tries to send and vetoes the channel close that follows,
 *       which leaves the connection - and Velocity's own player bookkeeping -
 *       intact;</li>
 *   <li>keep-alive packets are written directly, because nothing else is
 *       talking to the client while it is held;</li>
 *   <li>sounds are written directly too, since Velocity's {@code playSound}
 *       quietly does nothing for a player with no backend.</li>
 * </ul>
 *
 * <p>Everything else a held player sees - chat, action bar, title, boss bar -
 * goes through the ordinary public API, which writes straight to the client.
 *
 * <p>All of the above is resolved reflectively once at startup. If any piece is
 * missing - a Velocity version that moved or renamed it - {@link #supported()}
 * reports false and the plugin falls back to redirecting players to a hold
 * server, so a proxy update can never leave players stranded.
 */
public final class FreezeHold {

    private static final String PACKET = "com.velocitypowered.proxy.protocol.packet.";
    private static final String GUARD_NAME = "dontdisconnectme-hold-guard";

    private final boolean supported;
    private final String unsupportedReason;

    private final MethodHandle getConnection;
    private final MethodHandle getChannel;
    private final MethodHandle write;
    private final MethodHandle getState;
    private final MethodHandle getConnectedServer;
    private final MethodHandle getEntityId;
    private final Class<?> disconnectPacketType;
    private final Class<?> joinGamePacketType;
    private final Class<?> respawnPacketType;
    private final Constructor<?> keepAliveCtor;
    private final Method setRandomId;
    private final Constructor<?> soundCtor;
    private final Constructor<?> stopSoundCtor;

    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    /** One held player: the channel we are guarding and what we know about them. */
    private static final class Held {
        private final Channel channel;
        private final HoldGuard guard;
        private final Object connection;
        /** The player's entity id on the dead server, needed to emit sounds. */
        private final Integer entityId;
        private volatile long nextKeepAliveAt;
        /** Set while a reconnect is in flight, when the client is mid-handover. */
        private volatile boolean quiet;

        private Held(Channel channel, HoldGuard guard, Object connection, Integer entityId) {
            this.channel = channel;
            this.guard = guard;
            this.connection = connection;
            this.entityId = entityId;
        }
    }

    public FreezeHold() {
        boolean ok;
        String reason = "";
        MethodHandle connectionHandle = null;
        MethodHandle channelHandle = null;
        MethodHandle writeHandle = null;
        MethodHandle stateHandle = null;
        MethodHandle connectedServerHandle = null;
        MethodHandle entityIdHandle = null;
        Class<?> disconnectType = null;
        Class<?> joinGameType = null;
        Class<?> respawnType = null;
        Constructor<?> keepAlive = null;
        Method randomId = null;
        Constructor<?> sound = null;
        Constructor<?> stopSound = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> connectedPlayer = Class.forName(
                    "com.velocitypowered.proxy.connection.client.ConnectedPlayer");
            Class<?> minecraftConnection = Class.forName(
                    "com.velocitypowered.proxy.connection.MinecraftConnection");
            Class<?> serverConnection = Class.forName(
                    "com.velocitypowered.proxy.connection.backend.VelocityServerConnection");

            connectionHandle = lookup.unreflect(connectedPlayer.getMethod("getConnection"));
            connectedServerHandle = lookup.unreflect(connectedPlayer.getMethod("getConnectedServer"));
            channelHandle = lookup.unreflect(minecraftConnection.getMethod("getChannel"));
            writeHandle = lookup.unreflect(minecraftConnection.getMethod("write", Object.class));
            stateHandle = lookup.unreflect(minecraftConnection.getMethod("getState"));
            entityIdHandle = lookup.unreflect(serverConnection.getMethod("getEntityId"));

            disconnectType = Class.forName(PACKET + "DisconnectPacket");
            joinGameType = Class.forName(PACKET + "JoinGamePacket");
            respawnType = Class.forName(PACKET + "RespawnPacket");

            Class<?> keepAliveType = Class.forName(PACKET + "KeepAlivePacket");
            keepAlive = keepAliveType.getConstructor();
            randomId = keepAliveType.getMethod("setRandomId", long.class);

            sound = Class.forName(PACKET + "ClientboundSoundEntityPacket")
                    .getConstructor(Sound.class, Float.class, int.class);
            stopSound = Class.forName(PACKET + "ClientboundStopSoundPacket")
                    .getConstructor(SoundStop.class);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            ok = false;
            reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }

        this.supported = ok;
        this.unsupportedReason = reason;
        this.getConnection = connectionHandle;
        this.getChannel = channelHandle;
        this.write = writeHandle;
        this.getState = stateHandle;
        this.getConnectedServer = connectedServerHandle;
        this.getEntityId = entityIdHandle;
        this.disconnectPacketType = disconnectType;
        this.joinGamePacketType = joinGameType;
        this.respawnPacketType = respawnType;
        this.keepAliveCtor = keepAlive;
        this.setRandomId = randomId;
        this.soundCtor = sound;
        this.stopSoundCtor = stopSound;
    }

    /** False on a Velocity build whose internals this cannot reach. */
    public boolean supported() {
        return supported;
    }

    /** Why {@link #supported()} is false; empty when it is true. */
    public String unsupportedReason() {
        return unsupportedReason;
    }

    public boolean isHeld(UUID playerId) {
        return held.containsKey(playerId);
    }

    /**
     * Stops writing to a held player while we reconnect them.
     *
     * <p>Since 1.20.2 a server switch takes the client through the
     * configuration state, where the play packets written here do not exist. A
     * keep-alive or sound landing in that window is encoded against the wrong
     * packet registry and the client drops the connection with a protocol
     * error, so nothing is sent from the moment a reconnect starts until the
     * hold is handed back.
     */
    public void quiet(UUID playerId) {
        Held entry = held.get(playerId);
        if (entry != null) {
            entry.quiet = true;
        }
    }

    /**
     * EXPERIMENTAL. Drops the join-game and respawn packets Velocity sends when
     * handing the player to a backend, which is what makes the client throw its
     * world away and show the loading screen. Suppressing them should leave the
     * world on screen and make the switch look like an ordinary teleport - but
     * the client is then holding an entity id the new server knows nothing
     * about, so this is only safe once entity continuity is solved.
     */
    public void suppressWorldReset(UUID playerId, boolean suppress) {
        Held entry = held.get(playerId);
        if (entry != null) {
            entry.guard.suppressWorldReset = suppress;
        }
    }

    /**
     * Starts writing to a held player again after a reconnect attempt failed
     * and they are settled back on the proxy. Without this their keep-alives
     * would never resume and the client would time out.
     */
    public void resume(UUID playerId) {
        Held entry = held.get(playerId);
        if (entry != null) {
            entry.quiet = false;
        }
    }

    /**
     * Whether it is safe to send this player a play-state packet at all -
     * including through Velocity's own API, which does not check either.
     * Answers true when the state cannot be read, so behaviour is unchanged on
     * a proxy whose internals this cannot reach.
     */
    public boolean canReceivePlayPackets(Player player) {
        if (!supported) {
            return true;
        }
        try {
            Object connection = getConnection.invoke(player);
            if (connection == null) {
                return false;
            }
            Object state = getState.invoke(connection);
            return state != null && "PLAY".equals(((Enum<?>) state).name());
        } catch (Throwable ex) {
            return true;
        }
    }

    /**
     * True only when this held player's client is in the play state and we are
     * not in the middle of reconnecting them - the only time it is safe to
     * write a play packet straight to them.
     */
    private boolean writable(Held entry) {
        if (entry.quiet || !entry.channel.isActive()) {
            return false;
        }
        try {
            Object state = getState.invoke(entry.connection);
            return state != null && "PLAY".equals(((Enum<?>) state).name());
        } catch (Throwable ex) {
            return false;
        }
    }

    public int heldCount() {
        return held.size();
    }

    /**
     * Takes over the player's connection so the disconnect Velocity is about to
     * perform never reaches them. Must be called from the kick event, before
     * Velocity acts on its result.
     *
     * @return false if unsupported or the connection could not be taken over,
     *         in which case the caller should fall back to a hold server
     */
    public boolean hold(Player player) {
        if (!supported || held.containsKey(player.getUniqueId())) {
            return held.containsKey(player.getUniqueId());
        }
        try {
            Object connection = getConnection.invoke(player);
            if (connection == null) {
                return false;
            }
            Channel channel = (Channel) getChannel.invoke(connection);
            if (channel == null || !channel.isActive()) {
                return false;
            }

            Integer entityId = null;
            Object backend = getConnectedServer.invoke(player);
            if (backend != null) {
                entityId = (Integer) getEntityId.invoke(backend);
            }

            HoldGuard guard = new HoldGuard(disconnectPacketType, joinGamePacketType, respawnPacketType);
            // Outbound events start at the tail, so the tail-most handler is the
            // first to see the disconnect packet and the close that follows it.
            channel.pipeline().addLast(GUARD_NAME, guard);
            held.put(player.getUniqueId(), new Held(channel, guard, connection, entityId));
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * Hands the connection back to Velocity. After this the player can be
     * disconnected normally again, so call it once they are safely reconnected
     * or when giving up on them.
     */
    public void release(UUID playerId) {
        Held entry = held.remove(playerId);
        if (entry == null) {
            return;
        }
        entry.guard.disarm();
        try {
            if (entry.channel.pipeline().get(GUARD_NAME) != null) {
                entry.channel.pipeline().remove(GUARD_NAME);
            }
        } catch (RuntimeException ignored) {
            // The channel closed underneath us; nothing left to clean up.
        }
    }

    public void releaseAll() {
        for (UUID playerId : Map.copyOf(held).keySet()) {
            release(playerId);
        }
    }

    /**
     * Keeps held clients from timing out. Nothing else is talking to them, and
     * vanilla drops a connection that has been silent for 30 seconds.
     */
    public void tickKeepAlive(long now, long intervalMs) {
        for (Map.Entry<UUID, Held> entry : held.entrySet()) {
            Held entryValue = entry.getValue();
            if (now < entryValue.nextKeepAliveAt) {
                continue;
            }
            entryValue.nextKeepAliveAt = now + intervalMs;
            if (!entryValue.channel.isActive()) {
                release(entry.getKey());
                continue;
            }
            if (!writable(entryValue)) {
                continue;
            }
            try {
                Object packet = keepAliveCtor.newInstance();
                setRandomId.invoke(packet, ThreadLocalRandom.current().nextLong());
                write.invoke(entryValue.connection, packet);
            } catch (Throwable ignored) {
                // A failed keep-alive is not worth tearing the hold down for;
                // the next tick will try again.
            }
        }
    }

    /**
     * Plays a sound to a held player, which Velocity's own {@code playSound}
     * silently skips because they have no backend.
     *
     * @return false when the sound could not be sent
     */
    public boolean playSound(UUID playerId, Sound sound) {
        Held entry = held.get(playerId);
        if (entry == null || entry.entityId == null || !writable(entry)) {
            return false;
        }
        try {
            write.invoke(entry.connection, soundCtor.newInstance(sound, null, entry.entityId.intValue()));
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    /** @see #playSound(UUID, Sound) */
    public boolean stopSound(UUID playerId, SoundStop stop) {
        Held entry = held.get(playerId);
        if (entry == null || !writable(entry)) {
            return false;
        }
        try {
            write.invoke(entry.connection, stopSoundCtor.newInstance(stop));
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * Drops the disconnect Velocity sends when a backend dies, and refuses the
     * channel close that follows, leaving the player connected to the proxy.
     *
     * <p>Only outbound closes are vetoed - a player who quits closes the channel
     * from their side, which still tears down normally.
     */
    private static final class HoldGuard extends ChannelOutboundHandlerAdapter {

        private final Class<?> disconnectPacketType;
        private final Class<?> joinGamePacketType;
        private final Class<?> respawnPacketType;
        private volatile boolean armed = true;
        private volatile boolean suppressWorldReset;

        private HoldGuard(Class<?> disconnectPacketType, Class<?> joinGamePacketType,
                          Class<?> respawnPacketType) {
            this.disconnectPacketType = disconnectPacketType;
            this.joinGamePacketType = joinGamePacketType;
            this.respawnPacketType = respawnPacketType;
        }

        private void disarm() {
            armed = false;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (armed && disconnectPacketType.isInstance(msg)) {
                io.netty.util.ReferenceCountUtil.release(msg);
                promise.setSuccess();
                return;
            }
            if (suppressWorldReset
                    && (joinGamePacketType.isInstance(msg) || respawnPacketType.isInstance(msg))) {
                io.netty.util.ReferenceCountUtil.release(msg);
                promise.setSuccess();
                return;
            }
            super.write(ctx, msg, promise);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            if (armed) {
                promise.setSuccess();
                return;
            }
            super.close(ctx, promise);
        }
    }
}
