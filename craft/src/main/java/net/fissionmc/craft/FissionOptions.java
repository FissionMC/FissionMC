package net.fissionmc.craft;

import java.io.File;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

/** The slice of Paper's command line that the plugin system reads (vanilla has no such options). */
public final class FissionOptions {
    private FissionOptions() {}

    private static volatile OptionSet options;

    public static OptionSet get() {
        OptionSet o = options;
        if (o == null) {
            OptionParser parser = new OptionParser();
            parser.accepts("bukkit-settings").withRequiredArg().ofType(File.class).defaultsTo(new File("bukkit.yml"));
            parser.accepts("plugins").withRequiredArg().ofType(File.class).defaultsTo(new File("plugins"));
            parser.accepts("add-plugin").withRequiredArg().ofType(File.class).defaultsTo(new File[0]);
            parser.accepts("add-plugin-dir").withRequiredArg().ofType(File.class).defaultsTo(new File[0]);
            options = o = parser.parse();
        }
        return o;
    }
}
