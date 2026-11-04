package io.papermc.paper.configuration;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

/** FissionMC shim holding only what the plugin system needs; replaced if Paper's config system is ported. */
public final class PaperConfigurations {
    private PaperConfigurations() {}

    public static YamlConfiguration loadLegacyConfigFile(File configFile) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        if (configFile.exists()) {
            try {
                config.load(configFile);
            } catch (Exception ex) {
                throw new Exception("Failed to load configuration file: " + configFile.getName(), ex);
            }
        }
        return config;
    }
}
