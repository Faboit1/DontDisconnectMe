package top.cheesesmp.ddm.config;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.sound.Sound;

/**
 * One configured sound. Music discs are just sounds with a long
 * {@code loop-seconds} and {@code stop-on-exit: true}.
 */
public record SoundSpec(
        Key key,
        Sound.Source source,
        float volume,
        float pitch,
        long delayMs,
        long loopMs,
        boolean stopOnExit,
        int minProtocol,
        int maxProtocol) {

    public static List<SoundSpec> listFrom(ConfigSection parent, String path) {
        List<SoundSpec> out = new ArrayList<>();
        for (ConfigSection entry : parent.getSectionList(path)) {
            SoundSpec spec = from(entry);
            if (spec != null) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    /** Returns {@code null} for an unusable entry rather than failing the whole reload. */
    public static SoundSpec from(ConfigSection section) {
        String rawKey = section.getString("key", "");
        if (rawKey.isBlank()) {
            return null;
        }
        Key parsed;
        try {
            parsed = Key.key(rawKey.trim());
        } catch (InvalidKeyException ignored) {
            return null;
        }
        return new SoundSpec(
                parsed,
                section.getEnum(Sound.Source.class, "source", Sound.Source.MASTER),
                (float) section.getDouble("volume", 1.0D),
                (float) section.getDouble("pitch", 1.0D),
                Math.max(0L, section.getLong("delay-ms", 0L)),
                Math.max(0L, section.getLong("loop-seconds", 0L)) * 1000L,
                section.getBoolean("stop-on-exit", false),
                section.getInt("min-protocol", 0),
                section.getInt("max-protocol", 0));
    }

    /** Clients outside the configured protocol window silently skip this sound. */
    public boolean appliesTo(int protocol) {
        if (minProtocol > 0 && protocol < minProtocol) {
            return false;
        }
        return maxProtocol <= 0 || protocol <= maxProtocol;
    }

    public Sound toSound() {
        return Sound.sound(key, source, volume, pitch);
    }

    public boolean loops() {
        return loopMs > 0L;
    }
}
