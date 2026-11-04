package net.fissionmc.backend.mixin.util;

import com.mojang.jtracy.TracyClient;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.TracingExecutor;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/util/Util.java.patch */
@Mixin(Util.class)
public abstract class UtilMixin {
    @Shadow private static void onThreadException(final Thread thread, final Throwable throwable) {
        throw new AssertionError();
    }

    // Surface: public static final. Mixin requires non-public fields; the loader widens them.
    private static final TracingExecutor DIMENSION_DATA_IO_POOL = makeExtraIoExecutor("Dimension-Data-IO-Worker-"); // Paper - Separate dimension data IO pool
    private static final double COLLISION_EPSILON = 1.0E-7; // Paper - Check distance in entity interactions

    // Paper start - Separate dimension data IO pool
    private static TracingExecutor makeExtraIoExecutor(String namePrefix) {
        AtomicInteger atomicInteger = new AtomicInteger(1);
        return new TracingExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable);
            String string2 = namePrefix + atomicInteger.getAndIncrement();
            TracyClient.setThreadName(string2, namePrefix.hashCode());
            thread.setName(string2);
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler(UtilMixin::onThreadException);
            return thread;
        }));
    }
    // Paper end - Separate dimension data IO pool

    // TODO(behaviour): Paper's executor changes (makeExecutor priority, limited DOWNLOAD_POOL, worker thread count,
    // getNanos via System.nanoTime) and shutdown of DIMENSION_DATA_IO_POOL.
}
