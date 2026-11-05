package net.fissionmc.backend.blocks;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Lazy Bukkit block view of the blocks a piston moves/breaks (Paper's anonymous AbstractList in PistonBaseBlock#moveBlocks; Mixin cannot host anonymous classes). */
public final class PistonBlockList extends java.util.AbstractList<org.bukkit.block.Block> {
    private final Level level;
    private final List<BlockPos> moved;
    private final List<BlockPos> broken;

    public PistonBlockList(Level level, List<BlockPos> moved, List<BlockPos> broken) {
        this.level = level;
        this.moved = moved;
        this.broken = broken;
    }

    @Override
    public int size() {
        return this.moved.size() + this.broken.size();
    }

    @Override
    public org.bukkit.block.Block get(int index) {
        if (index >= this.size() || index < 0) {
            throw new ArrayIndexOutOfBoundsException(index);
        }

        BlockPos pos = index < this.moved.size() ? this.moved.get(index) : this.broken.get(index - this.moved.size());
        return org.bukkit.craftbukkit.block.CraftBlock.at(this.level, pos);
    }
}
