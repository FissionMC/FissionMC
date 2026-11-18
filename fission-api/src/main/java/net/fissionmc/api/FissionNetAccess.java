package net.fissionmc.api;

/** Where the server installs the service (internal: plugins call {@link FissionNet#get()}). */
public final class FissionNetAccess {
    private static volatile FissionNet instance;
    private FissionNetAccess() {}
    public static FissionNet instance() { return instance; }
    public static void install(FissionNet net) { instance = net; }
}
