package net.fissionmc.backend.boot;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import joptsimple.OptionSet;
import net.minecraft.server.WorldLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * State Paper's {@code Main.main(OptionSet)} keeps in locals, shared with the injections into vanilla
 * {@code Main.main(String[])} and the server constructors.
 *
 * <p>Paper (sources/net/minecraft/server/Main.java.patch) replaces vanilla's entry point with {@code main(OptionSet)}.
 * FissionMC keeps vanilla's method and runs it with the vanilla arguments Paper's options imply; every Paper hunk is an
 * injection that is active only while {@link #options()} is set (i.e. the server was started through
 * {@code org.bukkit.craftbukkit.Main}).
 */
public final class PaperStartup {
    private PaperStartup() {}

    private static volatile @Nullable OptionSet options;
    // Paper: java.util.concurrent.atomic.AtomicReference<WorldLoader.DataLoadContext> worldLoader (CraftBukkit)
    public static final AtomicReference<WorldLoader.DataLoadContext> WORLD_LOADER = new AtomicReference<>();
    // Paper - load config files early for access below if needed
    public static @Nullable YamlConfiguration bukkitConfiguration;
    public static @Nullable YamlConfiguration spigotConfiguration;
    public static net.minecraft.server.dedicated.@Nullable DedicatedServerSettings settings;

    public static @Nullable OptionSet options() {
        return options;
    }

    public static boolean active() {
        return options != null;
    }

    /** Rethrows a checked exception from a ported Paper body whose vanilla call site cannot declare it. */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> RuntimeException sneaky(final Throwable t) throws T {
        throw (T) t;
    }

    /** Stores Paper's options and returns the vanilla command line with the same meaning. */
    public static String[] begin(final OptionSet paperOptions) {
        options = paperOptions;
        final List<String> args = new ArrayList<>();
        if (paperOptions.has("nogui") || paperOptions.nonOptionArguments().contains("nogui")) {
            args.add("--nogui");
        }
        // Paper - fix SPIGOT-5824: the universe is --universe, else bukkit.yml settings.world-container. The bukkit.yml
        // lookup must happen before vanilla builds its File, so it is read here (Paper reads it at the same place later
        // through PaperConfigurations.loadLegacyConfigFile, which the main(String[]) injection still does).
        final File universe;
        if (paperOptions.has("universe")) {
            universe = (File) paperOptions.valueOf("universe");
        } else {
            final File bukkitFile = (File) paperOptions.valueOf("bukkit-settings");
            final YamlConfiguration bukkit = bukkitFile.isFile() ? YamlConfiguration.loadConfiguration(bukkitFile) : new YamlConfiguration();
            universe = new File(bukkit.getString("settings.world-container", "."));
        }
        args.add("--universe");
        args.add(universe.getPath());
        if (paperOptions.has("world")) {
            args.add("--world");
            args.add((String) paperOptions.valueOf("world"));
        }
        if (paperOptions.has("port")) {
            final int port = (Integer) paperOptions.valueOf("port");
            if (port > 0) {
                args.add("--port");
                args.add(Integer.toString(port));
            }
        }
        if (paperOptions.has("serverId")) {
            args.add("--serverId");
            args.add((String) paperOptions.valueOf("serverId"));
        }
        if (paperOptions.has("pidFile")) {
            args.add("--pidFile");
            args.add(paperOptions.valueOf("pidFile").toString());
        }
        for (final String flag : new String[] {"demo", "bonusChest", "safeMode", "jfrProfile", "initSettings", "forceUpgrade", "eraseCache", "recreateRegionFiles"}) {
            if (paperOptions.has(flag)) {
                args.add("--" + flag);
            }
        }
        return args.toArray(String[]::new);
    }
}
