package net.fissionmc.backend.mixin.world.level.block.state;

import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/block/state/BlockState.java.patch */
@Mixin(BlockState.class)
public abstract class BlockStateMixin {
    // Paper start - optimise getType calls
    private org.bukkit.@org.jspecify.annotations.Nullable Material cachedMaterial;

    public final org.bukkit.Material getBukkitMaterial() {
        if (this.cachedMaterial == null) {
            this.cachedMaterial = org.bukkit.craftbukkit.block.CraftBlockType.minecraftToBukkit(((BlockState) (Object) this).getBlock());
        }
        return this.cachedMaterial;
    }
    // Paper end - optimise getType calls
}
