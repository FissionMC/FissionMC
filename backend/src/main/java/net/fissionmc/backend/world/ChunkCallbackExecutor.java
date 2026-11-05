package net.fissionmc.backend.world;

/**
 * Paper: ChunkMap.CallbackExecutor (sources/net/minecraft/server/level/ChunkMap.java.patch).
 * FissionMC: Mixin cannot add nested classes, so the recursion-safe executor for Chunk loadCallback()/unloadCallback()
 * lives here; ChunkMapMixin holds one per ChunkMap (see {@link ChunkMapBridge}).
 */
// CraftBukkit start - recursion-safe executor for Chunk loadCallback() and unloadCallback()
public final class ChunkCallbackExecutor implements java.util.concurrent.Executor, Runnable {

    private final java.util.Queue<Runnable> queue = new java.util.ArrayDeque<>();

    @Override
    public void execute(Runnable runnable) {
        this.queue.add(runnable);
    }

    @Override
    public void run() {
        Runnable task;
        while ((task = this.queue.poll()) != null) {
            task.run();
        }
    }
}
// CraftBukkit end
