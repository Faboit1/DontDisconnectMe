package top.cheesesmp.ddm.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Copies the bundled config out on first run, then reads it back. */
public final class ConfigLoader {

    public static final String FILE_NAME = "config.yml";

    private final Path dataDirectory;

    public ConfigLoader(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public Path configPath() {
        return dataDirectory.resolve(FILE_NAME);
    }

    /**
     * @return true when the file had to be created
     */
    public boolean saveDefaultIfMissing() throws IOException {
        Path target = configPath();
        if (Files.exists(target)) {
            return false;
        }
        Files.createDirectories(dataDirectory);
        try (InputStream bundled = resource()) {
            if (bundled == null) {
                throw new IOException("config.yml is missing from the plugin jar");
            }
            Files.copy(bundled, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    public PluginConfig load() throws IOException {
        saveDefaultIfMissing();

        // Start from the bundled defaults so a user file that predates a new
        // option still ends up with a sensible value for it.
        Map<String, Object> defaults = readBundledDefaults();
        Map<String, Object> user;
        try (Reader reader = Files.newBufferedReader(configPath(), StandardCharsets.UTF_8)) {
            user = asMap(new Yaml().load(reader));
        }
        return PluginConfig.parse(ConfigSection.deepMerge(defaults, user));
    }

    public Map<String, Object> readBundledDefaults() throws IOException {
        try (InputStream bundled = resource()) {
            if (bundled == null) {
                return Collections.emptyMap();
            }
            try (Reader reader = new java.io.InputStreamReader(bundled, StandardCharsets.UTF_8)) {
                return asMap(new Yaml().load(reader));
            }
        }
    }

    private static InputStream resource() {
        return ConfigLoader.class.getClassLoader().getResourceAsStream(FILE_NAME);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object loaded) {
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }
}
