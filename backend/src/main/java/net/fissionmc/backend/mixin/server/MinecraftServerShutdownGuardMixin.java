package net.fissionmc.backend.mixin.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Shutdown must always finish. Paper moves the final steps (executor shutdown, {@code onServerExit}, which sets {@code hasFullyShutdown} and calls
 * {@code System.exit}) to the TAIL of {@code stopServer}; if anything in the middle throws (seen: a failure while saving/ending entity tracking),
 * TAIL never runs, the server thread dies, and Paper's "Server Shutdown Thread" waits for {@code hasFullyShutdown} forever, so the JVM never exits
 * (the user could not stop the backend). New file next to MinecraftServerMixin (does not edit it; applied after it by name order).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerShutdownGuardMixin {
    @Shadow private volatile boolean hasFullyShutdown;
    @Shadow private volatile Thread shutdownThread;
    @Shadow protected abstract void onServerExit();

    /** Test hook (-PtestStopThrows=1): a failure in the middle of stopServer, as seen in the field. */
    @Inject(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getConnection()Lnet/minecraft/server/network/ServerConnectionListener;", ordinal = 0))
    private void fission$simulatedStopFailure(CallbackInfo ci) {
        if (System.getProperty("fission.test.stop-throws") != null) throw new IllegalStateException("simulated failure during stopServer");
    }

    @WrapMethod(method = "stopServer")
    private void fission$alwaysFinishStop(Operation<Void> original) {
        if (System.getProperty("fission.test.no-guard") != null) { original.call(); return; } // test hook: show the old behaviour
        boolean failed = true;
        try {
            original.call();
            failed = false;
        } finally {
            // only the invocation that really performed the stop (the other one is cancelled by the hasStopped guard)
            if (this.shutdownThread == Thread.currentThread() && !this.hasFullyShutdown) {
                MinecraftServer.LOGGER.error("stopServer did not complete{}; forcing the final shutdown steps", failed ? " (exception)" : "");
                try {
                    net.minecraft.util.Util.shutdownExecutors();
                    this.onServerExit(); // sets hasFullyShutdown and exits the JVM (DedicatedServerMixin)
                } catch (Throwable t) {
                    MinecraftServer.LOGGER.error("final shutdown failed", t);
                }
                this.hasFullyShutdown = true;
                System.exit(70);
            }
        }
    }
}
