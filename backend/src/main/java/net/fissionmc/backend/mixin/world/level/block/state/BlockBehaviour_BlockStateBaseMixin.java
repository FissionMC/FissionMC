package net.fissionmc.backend.mixin.world.level.block.state;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/state/BlockBehaviour.java.patch (BlockStateBase) */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockBehaviour_BlockStateBaseMixin {
    @Shadow protected abstract BlockState asState();

    private org.bukkit.craftbukkit.block.data.@org.jspecify.annotations.Nullable CraftBlockData cachedBlockData;

    public org.bukkit.craftbukkit.block.data.CraftBlockData asBlockData() {
        if (this.cachedBlockData == null) this.cachedBlockData = org.bukkit.craftbukkit.block.data.CraftBlockData.createData(this.asState());
        return (org.bukkit.craftbukkit.block.data.CraftBlockData) this.cachedBlockData.clone();
    }
}
