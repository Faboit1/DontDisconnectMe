package top.cheesesmp.ddm.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A thin, forgiving view over the nested maps SnakeYAML hands back.
 *
 * <p>Everything is looked up by dotted path and everything takes a default, so a
 * config that is missing half its keys still produces a working plugin instead
 * of a stack trace.
 */
public final class ConfigSection {

    private static final ConfigSection EMPTY = new ConfigSection(Collections.emptyMap());

    private final Map<String, Object> map;

    private ConfigSection(Map<String, Object> map) {
        this.map = map;
    }

    public static ConfigSection empty() {
        return EMPTY;
    }

    public static ConfigSection of(Object raw) {
        Map<String, Object> normalised = normalise(raw);
        return normalised.isEmpty() ? EMPTY : new ConfigSection(normalised);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalise(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) {
            return Collections.emptyMap();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    public Map<String, Object> raw() {
        return map;
    }

    public Set<String> keys() {
        return map.keySet();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** Resolves a dotted path, returning {@code null} when any hop is missing. */
    public Object get(String path) {
        Map<String, Object> current = map;
        int start = 0;
        while (true) {
            int dot = path.indexOf('.', start);
            if (dot < 0) {
                return current.get(path.substring(start));
            }
            Object next = current.get(path.substring(start, dot));
            Map<String, Object> child = normalise(next);
            if (child.isEmpty()) {
                return null;
            }
            current = child;
            start = dot + 1;
        }
    }

    public boolean has(String path) {
        return get(path) != null;
    }

    /** Never null - a missing section reads as an empty one. */
    public ConfigSection section(String path) {
        return of(get(path));
    }

    public String getString(String path, String def) {
        Object value = get(path);
        return value == null ? def : String.valueOf(value);
    }

    public boolean getBoolean(String path, boolean def) {
        Object value = get(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return def;
    }

    public int getInt(String path, int def) {
        return (int) getLong(path, def);
    }

    public long getLong(String path, long def) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public double getDouble(String path, double def) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** Clamps to a sane window so a typo cannot melt the proxy. */
    public long getLongClamped(String path, long def, long min, long max) {
        return Math.max(min, Math.min(max, getLong(path, def)));
    }

    public List<String> getStringList(String path) {
        Object value = get(path);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element != null) {
                    out.add(String.valueOf(element));
                }
            }
        } else if (value instanceof String single && !single.isEmpty()) {
            out.add(single);
        }
        return out;
    }

    public List<ConfigSection> getSectionList(String path) {
        Object value = get(path);
        List<ConfigSection> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                ConfigSection child = of(element);
                if (!child.isEmpty()) {
                    out.add(child);
                }
            }
        }
        return out;
    }

    public <E extends Enum<E>> E getEnum(Class<E> type, String path, E def) {
        String value = getString(path, null);
        if (value == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return def;
        }
    }

    /**
     * Recursively lays {@code overlay} on top of {@code base}. Maps merge key by
     * key; everything else (scalars, lists) is replaced wholesale, which is what
     * you want for things like sound lists and chat bodies.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            Object existing = out.get(entry.getKey());
            Object replacement = entry.getValue();
            if (existing instanceof Map<?, ?> && replacement instanceof Map<?, ?>) {
                out.put(entry.getKey(), deepMerge(
                        normalise(existing),
                        normalise(replacement)));
            } else {
                out.put(entry.getKey(), replacement);
            }
        }
        return out;
    }
}
