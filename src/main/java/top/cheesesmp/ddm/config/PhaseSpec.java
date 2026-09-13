package top.cheesesmp.ddm.config;

import java.time.Duration;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;

/**
 * Everything a single phase (kicked / waiting / reconnecting / success /
 * failed) is allowed to show and play. Each element carries its own
 * {@code enabled} flag so servers can use only the bits they want.
 */
public record PhaseSpec(
        boolean enabled,
        long updateIntervalMs,
        long durationMs,
        ChatSpec chat,
        ActionBarSpec actionBar,
        TitleSpec title,
        BossBarSpec bossBar,
        List<SoundSpec> sounds,
        RetrySoundSpec retrySound) {

    /** How a boss bar's progress should behave while the phase is on screen. */
    public enum ProgressMode {
        /** Always the configured value. */
        STATIC,
        /** Drains from full to empty between reconnect attempts. */
        RETRY_COUNTDOWN,
        /** Fills up as the player advances through the re-entry queue. */
        QUEUE
    }

    public record ChatSpec(boolean enabled, long repeatMs, List<String> lines) {
        static ChatSpec from(ConfigSection section) {
            return new ChatSpec(
                    section.getBoolean("enabled", false),
                    Math.max(0L, section.getLong("repeat-seconds", 0L)) * 1000L,
                    List.copyOf(section.getStringList("lines")));
        }

        public boolean repeats() {
            return repeatMs > 0L;
        }
    }

    public record ActionBarSpec(boolean enabled, String text) {
        static ActionBarSpec from(ConfigSection section) {
            return new ActionBarSpec(
                    section.getBoolean("enabled", false),
                    section.getString("text", ""));
        }
    }

    public record TitleSpec(boolean enabled, String title, String subtitle,
                            long fadeInMs, long stayMs, long fadeOutMs) {
        static TitleSpec from(ConfigSection section) {
            return new TitleSpec(
                    section.getBoolean("enabled", false),
                    section.getString("title", ""),
                    section.getString("subtitle", ""),
                    Math.max(0L, section.getLong("fade-in-ms", 0L)),
                    Math.max(50L, section.getLong("stay-ms", 1500L)),
                    Math.max(0L, section.getLong("fade-out-ms", 0L)));
        }

        public net.kyori.adventure.title.Title.Times times() {
            return net.kyori.adventure.title.Title.Times.times(
                    Duration.ofMillis(fadeInMs),
                    Duration.ofMillis(stayMs),
                    Duration.ofMillis(fadeOutMs));
        }
    }

    public record BossBarSpec(boolean enabled, String text, BossBar.Color color,
                              BossBar.Overlay overlay, float progress, ProgressMode progressMode) {
        static BossBarSpec from(ConfigSection section) {
            float progress = (float) section.getDouble("progress", 1.0D);
            return new BossBarSpec(
                    section.getBoolean("enabled", false),
                    section.getString("text", ""),
                    section.getEnum(BossBar.Color.class, "color", BossBar.Color.RED),
                    section.getEnum(BossBar.Overlay.class, "overlay", BossBar.Overlay.PROGRESS),
                    Math.max(0f, Math.min(1f, progress)),
                    section.getEnum(ProgressMode.class, "progress-mode", ProgressMode.STATIC));
        }
    }

    /** A one-shot blip played on every reconnect attempt during a phase. */
    public record RetrySoundSpec(boolean enabled, SoundSpec sound) {
        static RetrySoundSpec from(ConfigSection section) {
            if (section.isEmpty() || !section.getBoolean("enabled", false)) {
                return new RetrySoundSpec(false, null);
            }
            SoundSpec sound = SoundSpec.from(section);
            return new RetrySoundSpec(sound != null, sound);
        }
    }

    public static PhaseSpec from(ConfigSection section) {
        return new PhaseSpec(
                section.getBoolean("enabled", true),
                section.getLongClamped("update-interval-ms", 500L, 0L, 60_000L),
                Math.max(0L, section.getLong("duration-ms", 0L)),
                ChatSpec.from(section.section("chat")),
                ActionBarSpec.from(section.section("action-bar")),
                TitleSpec.from(section.section("title")),
                BossBarSpec.from(section.section("boss-bar")),
                SoundSpec.listFrom(section, "sounds"),
                RetrySoundSpec.from(section.section("retry-sound")));
    }

    public boolean updates() {
        return updateIntervalMs > 0L;
    }
}
