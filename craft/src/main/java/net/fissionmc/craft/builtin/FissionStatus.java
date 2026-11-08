package net.fissionmc.craft.builtin;

import java.util.List;
import java.util.function.Supplier;

/** Bridge from the backend module (which knows the master statistics) to the {@code /fission} command in craft. */
public final class FissionStatus {
    private FissionStatus() {}

    /** `/fission debug ...` (console / ops, only active with -Dfission.debug): arguments after "debug" in, output lines out; set by the backend. */
    public static volatile java.util.function.Function<String[], List<String>> debug = a -> List.of("debug commands are not available (start with -Dfission.debug)");

    public static volatile Supplier<List<String>> provider = () -> List.of("no master statistics available");
}
