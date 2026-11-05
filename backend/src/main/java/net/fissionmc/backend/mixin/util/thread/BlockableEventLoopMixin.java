package net.fissionmc.backend.mixin.util.thread;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/util/thread/BlockableEventLoop.java.patch */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin<R extends Runnable> {
    @Shadow public abstract void schedule(R r);
    @Shadow @org.spongepowered.asm.mixin.Final private java.util.Queue<R> pendingRunnables;
    @Shadow protected abstract void doRunTask(R task);
    @Shadow public abstract boolean isSameThread();

    // Paper start
    public final boolean executeAllRecentInternalTasks() {
        final int pending = this.pendingRunnables.size();

        // note: due to possible recursive execution, we may execute more tasks than we want to

        int ran = 0;

        for (int i = 0; i < pending; ++i) {
            final R run = this.pendingRunnables.poll();
            if (run == null) {
                // recursion
                break;
            }
            this.doRunTask(run);
            ++ran;
        }

        return ran > 0;
    }

    public boolean runTaskIf(final java.util.function.Predicate<? super R> predicate) {
        if (!this.isSameThread()) {
            throw new IllegalStateException();
        }

        final R run = this.pendingRunnables.peek();
        if (run == null || (predicate != null && !predicate.test(run))) {
            return false;
        }

        this.pendingRunnables.remove();

        this.doRunTask(run);

        return true;
    }
    // Paper end

    // Paper start
    public void scheduleOnMain(Runnable runnable) {
        // postToMainThread does not work the same as older versions of mc
        // This method is actually used to create a TickTask, which can then be posted onto main
        this.schedule(((BlockableEventLoop<R>) (Object) this).wrapRunnable(runnable)); // FissionMC: wrapRunnable is declared by TaskScheduler, not shadowable here
    }
    // Paper end
}
