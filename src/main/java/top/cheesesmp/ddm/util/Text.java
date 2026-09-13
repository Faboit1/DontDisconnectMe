package top.cheesesmp.ddm.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import java.util.Locale;

/** MiniMessage helpers shared by every message the plugin sends. */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component parse(String raw, TagResolver resolver) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(raw, resolver);
    }

    public static Component parse(String raw) {
        return parse(raw, TagResolver.empty());
    }

    public static String plain(Component component) {
        return plain(component, Locale.US);
    }

    /**
     * Flattens a component to text, translating any translatable parts first.
     * Kick reasons from Velocity are translatable, so without this the player
     * would be shown raw keys like {@code velocity.error.connected-server-error}.
     */
    public static String plain(Component component, Locale locale) {
        if (component == null) {
            return "";
        }
        Component rendered = GlobalTranslator.render(component, locale == null ? Locale.US : locale);
        return PlainTextComponentSerializer.plainText().serialize(rendered);
    }

    /** MiniMessage-escapes text taken from elsewhere so kick reasons cannot inject tags. */
    public static String escape(String raw) {
        return raw == null ? "" : MINI.escapeTags(raw);
    }

    public static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }
}
