package io.papermc.paper;

/** FissionMC shim: we do not bundle spark, so an installed spark plugin is always preferred. */
public final class SparksFly {
    private SparksFly() {}

    public static boolean isPluginPreferred() {
        return true;
    }
}
