package org.bukkit.craftbukkit.util;

/** FissionMC shim: Paper reads these from a build-generated apiVersioning.json; we pin them. */
public final class Versioning {
    private Versioning() {}

    public static String getBukkitVersion() {
        return "26.3-R0.1-SNAPSHOT";
    }

    public static String getCurrentApiVersion() {
        return "26.3";
    }
}
