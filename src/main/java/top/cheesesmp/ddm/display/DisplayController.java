package top.cheesesmp.ddm.display;

import com.velocitypowered.api.proxy.Player;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import top.cheesesmp.ddm.config.PhaseSpec;
import top.cheesesmp.ddm.config.SoundSpec;
import top.cheesesmp.ddm.hold.FreezeHold;
import top.cheesesmp.ddm.reconnect.ReconnectSession;
import top.cheesesmp.ddm.util.Text;

/**
 * Turns a phase's configuration into what the player actually sees and hears:
 * chat, action bar, title, boss bar and sounds - each independently optional.
 */
public final class DisplayController {

    /** Where debug lines go; supplied by the plugin so this class stays testable. */
    @FunctionalInterface
    public interface DebugSink {
        void log(String message);
    }

    private final FreezeHold freezeHold;
    private volatile DebugSink debug = message -> { };

    public DisplayController(FreezeHold freezeHold) {
        this.freezeHold = freezeHold;
    }

    public void debugSink(DebugSink sink) {
        this.debug = sink == null ? message -> { } : sink;
    }

    /**
     * Renders everything that is due for the session's current phase.
     * Called on every plugin tick; it decides internally what has come around.
     */
    public void update(Player player, ReconnectSession session, TagResolver resolver, long now) {
        PhaseSpec spec = session.spec();
        if (spec == null || !spec.enabled()) {
            clearBossBar(player, session);
            return;
        }

        if (session.consumePhaseEntered()) {
            enterPhaseAudio(player, session, spec);
        }
        sendChatIfDue(player, session, spec, resolver, now);
        playSoundsIfDue(player, session, spec, now);

        if (now < session.nextDisplayAt()) {
            return;
        }
        session.nextDisplayAt(spec.updates() ? now + spec.updateIntervalMs() : Long.MAX_VALUE);

        if (spec.actionBar().enabled() && !Text.isBlank(spec.actionBar().text())) {
            player.sendActionBar(Text.parse(spec.actionBar().text(), resolver));
        }

        PhaseSpec.TitleSpec title = spec.title();
        if (title.enabled() && !(Text.isBlank(title.title()) && Text.isBlank(title.subtitle()))) {
            player.showTitle(Title.title(
                    Text.parse(title.title(), resolver),
                    Text.parse(title.subtitle(), resolver),
                    title.times()));
        }

        renderBossBar(player, session, spec, resolver, now);
    }

    /**
     * Clears the deck before a phase's own sounds start: first anything this
     * plugin left playing (the previous phase's disc), then whatever the phase
     * asks to silence - normally the game's background music and any other
     * record - so only one track is ever playing.
     */
    private void enterPhaseAudio(Player player, ReconnectSession session, PhaseSpec spec) {
        for (Key key : session.drainStoppableSounds()) {
            stop(player, SoundStop.named(key));
        }
        for (SoundStop stop : spec.stopSoundsFirst()) {
            stop(player, stop);
        }
    }

    private void sendChatIfDue(Player player, ReconnectSession session, PhaseSpec spec,
                               TagResolver resolver, long now) {
        PhaseSpec.ChatSpec chat = spec.chat();
        if (!chat.enabled() || chat.lines().isEmpty() || now < session.nextChatAt()) {
            return;
        }
        for (String line : chat.lines()) {
            player.sendMessage(line.isEmpty() ? Component.empty() : Text.parse(line, resolver));
        }
        session.nextChatAt(chat.repeats() ? now + chat.repeatMs() : Long.MAX_VALUE);
    }

    private void playSoundsIfDue(Player player, ReconnectSession session, PhaseSpec spec, long now) {
        List<SoundSpec> sounds = spec.sounds();
        if (sounds.isEmpty()) {
            return;
        }
        if (player.getCurrentServer().isEmpty() && !freezeHold.isHeld(player.getUniqueId())) {
            // Velocity silently drops sounds for a player who is between
            // servers. Leave the schedule alone and play them once they land.
            // A held player has no server either, but we send their sounds
            // ourselves, so they are fine.
            return;
        }
        long[] schedule = session.soundSchedule();
        int protocol = player.getProtocolVersion().getProtocol();

        for (int i = 0; i < sounds.size() && i < schedule.length; i++) {
            long due = schedule[i];
            if (due == Long.MAX_VALUE || now < due) {
                continue;
            }
            SoundSpec sound = sounds.get(i);
            if (!sound.appliesTo(protocol)) {
                session.soundScheduleAt(i, Long.MAX_VALUE);
                continue;
            }
            play(player, sound);
            if (sound.stopOnExit()) {
                session.rememberStoppable(sound.key());
            }
            session.soundScheduleAt(i, sound.loops() ? now + sound.loopMs() : Long.MAX_VALUE);
        }
    }

    /** Plays the one-shot blip configured for a retry attempt, if any. */
    public void playRetrySound(Player player, PhaseSpec spec) {
        PhaseSpec.RetrySoundSpec retry = spec == null ? null : spec.retrySound();
        if (retry == null || !retry.enabled() || retry.sound() == null) {
            return;
        }
        SoundSpec sound = retry.sound();
        if (sound.appliesTo(player.getProtocolVersion().getProtocol())) {
            play(player, sound);
        }
    }

    /**
     * Velocity only implements the {@link Sound.Emitter} overload of
     * {@code playSound}; the position-less one is an Adventure default that does
     * nothing at all, so every sound has to be emitted from the player.
     */
    private void play(Player player, SoundSpec sound) {
        boolean heldOnProxy = freezeHold.isHeld(player.getUniqueId());
        debug.log("playing " + sound.key().asString() + " for " + player.getUsername()
                + " (protocol " + player.getProtocolVersion().getProtocol()
                + ", " + (heldOnProxy ? "held on proxy" : "server " + player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName()).orElse("none")) + ")");
        if (heldOnProxy && freezeHold.playSound(player.getUniqueId(), sound.toSound())) {
            return;
        }
        player.playSound(sound.toSound(), Sound.Emitter.self());
    }

    private void renderBossBar(Player player, ReconnectSession session, PhaseSpec spec,
                               TagResolver resolver, long now) {
        PhaseSpec.BossBarSpec config = spec.bossBar();
        if (!config.enabled() || Text.isBlank(config.text())) {
            clearBossBar(player, session);
            return;
        }

        Component name = Text.parse(config.text(), resolver);
        float progress = progressFor(session, config, now);

        BossBar bar = session.bossBar();
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, config.color(), config.overlay());
            session.bossBar(bar);
            player.showBossBar(bar);
            return;
        }
        bar.name(name);
        bar.color(config.color());
        bar.overlay(config.overlay());
        bar.progress(progress);
    }

    private float progressFor(ReconnectSession session, PhaseSpec.BossBarSpec config, long now) {
        return switch (config.progressMode()) {
            case RETRY_COUNTDOWN -> {
                long remaining = session.nextAttemptAt() - now;
                if (remaining <= 0L) {
                    yield 0f;
                }
                yield clamp((float) remaining / (float) session.retryWindowMs());
            }
            case QUEUE -> {
                int size = session.queueSize();
                int position = session.queuePosition();
                if (size <= 0 || position <= 0) {
                    yield config.progress();
                }
                yield clamp((float) (size - position + 1) / (float) size);
            }
            case STATIC -> config.progress();
        };
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public void clearBossBar(Player player, ReconnectSession session) {
        BossBar bar = session.bossBar();
        if (bar != null) {
            player.hideBossBar(bar);
            session.bossBar(null);
        }
    }

    /** Stops every looping sound this session started - the "music off" switch. */
    public void stopSounds(Player player, ReconnectSession session, List<String> extraKeys) {
        for (Key key : session.drainStoppableSounds()) {
            stop(player, SoundStop.named(key));
        }
        for (String raw : extraKeys) {
            try {
                stop(player, SoundStop.named(Key.key(raw.trim())));
            } catch (net.kyori.adventure.key.InvalidKeyException ignored) {
                // A bad key in the config should never break a reconnect.
            }
        }
    }

    /** Mirrors {@link #play}: a held player's stop packets are sent by us. */
    private void stop(Player player, SoundStop stop) {
        if (freezeHold.isHeld(player.getUniqueId()) && freezeHold.stopSound(player.getUniqueId(), stop)) {
            return;
        }
        player.stopSound(stop);
    }

    /** Wipes anything still on screen when a session ends early. */
    public void clearAll(Player player, ReconnectSession session, List<String> extraKeys) {
        stopSounds(player, session, extraKeys);
        clearBossBar(player, session);
        player.clearTitle();
        player.sendActionBar(Component.empty());
    }
}
