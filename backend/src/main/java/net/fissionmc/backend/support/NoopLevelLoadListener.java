package net.fissionmc.backend.support;

import net.minecraft.server.level.progress.LevelLoadListener;

/** FissionMC: LevelLoadListener.noop() does not exist in the vanilla jar. A top-level class, because Mixin cannot load anonymous classes declared inside a mixin (NoClassDefFoundError ...$Anonymous$...). */
public final class NoopLevelLoadListener implements LevelLoadListener {
    @Override public void start(final LevelLoadListener.Stage stage, final int count) {}
    @Override public void update(final LevelLoadListener.Stage stage, final int count, final int total) {}
    @Override public void finish(final LevelLoadListener.Stage stage) {}
    @Override public void updateFocus(final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, final net.minecraft.world.level.ChunkPos pos) {}
}
