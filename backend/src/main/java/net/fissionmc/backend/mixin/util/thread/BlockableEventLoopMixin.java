package net.fissionmc.backend.mixin.util.thread;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/util/thread/BlockableEventLoop.java.patch */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin<R extends Runnable> {
    @Shadow public abstract void schedule(R r);

    // Paper start
    public void scheduleOnMain(Runnable runnable) {
        // postToMainThread does not work the same as older versions of mc
        // This method is actually used to create a TickTask, which can then be posted onto main
        this.schedule(((BlockableEventLoop<R>) (Object) this).wrapRunnable(runnable)); // FissionMC: wrapRunnable is declared by TaskScheduler, not shadowable here
    }
    // Paper end
}
