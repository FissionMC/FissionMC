package net.fissionmc.craft.builtin;

import java.util.List;
import java.util.function.Supplier;

/** Bridge from the backend module (which knows the master statistics) to the {@code /fission} command in craft. */
public final class FissionStatus {
    private FissionStatus() {}

    public static volatile Supplier<List<String>> provider = () -> List.of("no master statistics available");
}
