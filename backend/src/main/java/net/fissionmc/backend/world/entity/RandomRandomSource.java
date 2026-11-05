package net.fissionmc.backend.world.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

/**
 * Paper - Share random for entities to make them more random. Paper's private nested class Entity.RandomRandomSource
 * (paper-server/patches/sources/net/minecraft/world/entity/Entity.java.patch), moved here because a mixin cannot add a nested class.
 */
public final class RandomRandomSource extends java.util.Random implements net.minecraft.world.level.levelgen.BitRandomSource {
    private static final Logger LOGGER = LogUtils.getLogger();
    private boolean locked = false;

    @Override
    public synchronized void setSeed(long seed) {
        if (locked) {
            LOGGER.error("Ignoring setSeed on Entity.SHARED_RANDOM", new Throwable());
        } else {
            super.setSeed(seed);
            locked = true;
        }
    }

    @Override
    public RandomSource fork() {
        return new net.minecraft.world.level.levelgen.LegacyRandomSource(this.nextLong());
    }

    @Override
    public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() {
        return new net.minecraft.world.level.levelgen.LegacyRandomSource.LegacyPositionalRandomFactory(this.nextLong());
    }

    // these below are added to fix reobf issues that I don't wanna deal with right now
    @Override
    public int next(int bits) {
        return super.next(bits);
    }

    @Override
    public int nextInt(int origin, int bound) {
        return net.minecraft.world.level.levelgen.BitRandomSource.super.nextInt(origin, bound);
    }

    @Override
    public long nextLong() {
        return net.minecraft.world.level.levelgen.BitRandomSource.super.nextLong();
    }

    @Override
    public int nextInt() {
        return net.minecraft.world.level.levelgen.BitRandomSource.super.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return net.minecraft.world.level.levelgen.BitRandomSource.super.nextInt(bound);
    }

    @Override
    public boolean nextBoolean() {
        return net.minecraft.world.level.levelgen.BitRandomSource.super.nextBoolean();
    }

    @Override
    public float nextFloat() {
        return net.minecraft.world.level.levelgen.BitRandomSource.super.nextFloat();
    }

    @Override
    public double nextDouble() {
        return net.minecraft.world.level.levelgen.BitRandomSource.super.nextDouble();
    }

    @Override
    public double nextGaussian() {
        return super.nextGaussian();
    }
}
