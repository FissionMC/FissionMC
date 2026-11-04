package org.bukkit.craftbukkit.util;

import org.bukkit.plugin.PluginDescriptionFile;

/** FissionMC shim with only what the plugin system needs; Paper's full class is ported with the item/block layer. */
public final class CraftMagicNumbers {
    private CraftMagicNumbers() {}

    public static boolean isLegacy(PluginDescriptionFile pdf) {
        return pdf.getAPIVersion() == null;
    }
}
