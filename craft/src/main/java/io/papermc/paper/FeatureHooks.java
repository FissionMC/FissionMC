package io.papermc.paper;

/** FissionMC shim: Paper's version lives in a vanilla-source patch we do not apply. */
public final class FeatureHooks {
    private FeatureHooks() {}

    public static void flushAsyncAppenders() {
        // Paper flushes its async log4j appenders here; we have none.
    }
}
