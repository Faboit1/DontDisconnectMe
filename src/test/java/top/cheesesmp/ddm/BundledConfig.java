package top.cheesesmp.ddm;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import top.cheesesmp.ddm.config.PluginConfig;

/** Loads the config.yml that ships in the jar, so the tests check the real defaults. */
final class BundledConfig {

    private BundledConfig() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> raw() throws IOException {
        try (InputStream stream = BundledConfig.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (stream == null) {
                throw new IOException("config.yml is not on the test classpath");
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return (Map<String, Object>) new Yaml().load(reader);
            }
        }
    }

    static PluginConfig load() throws IOException {
        return PluginConfig.parse(raw());
    }
}
