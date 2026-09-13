package top.cheesesmp.ddm.util;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import top.cheesesmp.ddm.config.DurationFormat;

/** Collects the {@code <placeholder>} tags available to a message. */
public final class Placeholders {

    private final DurationFormat format;
    private final List<TagResolver> resolvers = new ArrayList<>();

    public Placeholders(DurationFormat format) {
        this.format = format;
    }

    public Placeholders text(String key, String value) {
        resolvers.add(Placeholder.unparsed(key, value == null ? "" : value));
        return this;
    }

    /** Adds a value that is allowed to carry its own formatting. */
    public Placeholders component(String key, Component value) {
        resolvers.add(Placeholder.component(key, value == null ? Component.empty() : value));
        return this;
    }

    public Placeholders number(String key, long value) {
        return text(key, Long.toString(value));
    }

    /** Adds {@code <key>}, {@code <key_clock>} and {@code <key_seconds>} in one go. */
    public Placeholders duration(String key, long millis) {
        long safe = Math.max(0L, millis);
        text(key, format.format(safe));
        text(key + "_clock", format.formatClock(safe));
        text(key + "_seconds", Long.toString(safe / 1000L));
        return this;
    }

    public TagResolver build() {
        return TagResolver.resolver(resolvers);
    }
}
