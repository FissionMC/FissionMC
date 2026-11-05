package net.fissionmc.backend.mixin.util;

import com.mojang.jtracy.TracyClient;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.TracingExecutor;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

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

    @Shadow @org.spongepowered.asm.mixin.Final private static org.slf4j.Logger LOGGER;

    // Paper start - Limit download pool size
    @WrapOperation(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;makeIoExecutor(Ljava/lang/String;Z)Lnet/minecraft/TracingExecutor;"))
    private static TracingExecutor paper$limitDownloadPool(String name, boolean daemon, Operation<TracingExecutor> original) {
        if (!"Download-".equals(name)) return original.call(name, daemon);
        // FissionMC: Paper's anonymous ThreadFactory as a lambda (mixins cannot add anonymous classes)
        final AtomicInteger count = new AtomicInteger();
        return new TracingExecutor(Executors.newFixedThreadPool(4, run -> {
            Thread ret = new Thread(run);
            ret.setDaemon(true);
            ret.setName("Download-" + count.getAndIncrement());
            ret.setUncaughtExceptionHandler((Thread thread, Throwable throwable) -> {
                LOGGER.error("Uncaught exception in thread " + thread.getName(), throwable);
            });
            return ret;
        }));
    }
    // Paper end - Limit download pool size

    @Inject(method = "getNanos", at = @At("HEAD"), cancellable = true)
    private static void paper$getNanos(CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(System.nanoTime()); // Paper
    }

    // makeExecutor(name) is only called for BACKGROUND_EXECUTOR ("Main"), where Paper passes priorityModifier -1.
    @WrapOperation(method = "lambda$makeExecutor$0", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ForkJoinWorkerThread;setName(Ljava/lang/String;)V"))
    private static void paper$deprioritize(java.util.concurrent.ForkJoinWorkerThread thread, String name, Operation<Void> original) {
        thread.setPriority(Thread.NORM_PRIORITY + -1); // Paper - Deprioritize over main
        original.call(thread, name);
    }

    @WrapOperation(method = "makeExecutor", at = @At(value = "NEW", target = "(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;Ljava/lang/Thread$UncaughtExceptionHandler;Z)Ljava/util/concurrent/ForkJoinPool;"))
    private static java.util.concurrent.ForkJoinPool paper$doNotExpireThreads(int threads, java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory factory, Thread.UncaughtExceptionHandler handler, boolean asyncMode, Operation<java.util.concurrent.ForkJoinPool> original) {
        return new java.util.concurrent.ForkJoinPool(threads, factory, handler, asyncMode, 0, Integer.MAX_VALUE, 1, null, 365, java.util.concurrent.TimeUnit.DAYS); // Paper - do not expire threads
    }

    @Inject(method = "maxAllowedExecutorThreads", at = @At("HEAD"), cancellable = true)
    private static void paper$maxAllowedExecutorThreads(CallbackInfoReturnable<Integer> cir) {
        // Paper start - Perf: use simpler thread pool that allows 1 thread and reduce worldgen thread worker count for low core count CPUs
        final int cpus = ca.spottedleaf.concurrentutil.numa.OSNuma.getNativeInstance().getTotalCores()  / 2;
        int maxExecutorThreads;
        if (cpus <= 4) {
            maxExecutorThreads = cpus <= 2 ? 1 : 2;
        } else if (cpus <= 8) {
            // [5, 8]
            maxExecutorThreads = Math.max(3, cpus - 2);
        } else {
            maxExecutorThreads = cpus * 2 / 3;
        }
        maxExecutorThreads = Math.min(8, maxExecutorThreads);
        cir.setReturnValue(Integer.getInteger("Paper.WorkerThreadCount", maxExecutorThreads));
        // Paper end - Perf: use simpler thread pool that allows 1 thread and reduce worldgen thread worker count for low core count CPUs
    }
}
