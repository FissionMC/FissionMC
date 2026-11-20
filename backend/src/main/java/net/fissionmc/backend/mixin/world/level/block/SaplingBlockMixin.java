package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/level/block/SaplingBlock.java.patch - advanceTree captures the generated blocks and fires StructureGrowEvent. */
@Mixin(SaplingBlock.class)
public abstract class SaplingBlockMixin {
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static org.bukkit.@org.jspecify.annotations.Nullable TreeType treeType; // CraftBukkit

    // CraftBukkit start
    @WrapOperation(method = "advanceTree", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/grower/TreeGrower;growTree(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)Z"))
    private boolean paper$captureTree(final TreeGrower grower, final ServerLevel level, final ChunkGenerator generator, final BlockPos pos, final BlockState state, final RandomSource random, final Operation<Boolean> original) {
        if (level.captureTreeGeneration) {
            return original.call(grower, level, generator, pos, state, random);
        }
        level.captureTreeGeneration = true;
        java.util.List<org.bukkit.craftbukkit.block.CraftBlockState> capturedBlockStates;
        org.bukkit.TreeType capturedTreeType;
        boolean result;
        try {
            result = original.call(grower, level, generator, pos, state, random);
        } finally {
            level.captureTreeGeneration = false;

            capturedBlockStates = new java.util.ArrayList<>(level.capturedBlockStates.values());
            level.capturedBlockStates.clear();

            capturedTreeType = SaplingBlock.treeType;
            SaplingBlock.treeType = null;
        }
        if (!capturedBlockStates.isEmpty()) {
            final org.bukkit.Location location = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level);
            org.bukkit.event.world.StructureGrowEvent event = null;
            if (capturedTreeType != null) {
                event = new org.bukkit.event.world.StructureGrowEvent(location, capturedTreeType, false, null, (java.util.List<org.bukkit.block.BlockState>) (java.util.List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
                org.bukkit.Bukkit.getPluginManager().callEvent(event);
            }
            if (event == null || !event.isCancelled()) {
                for (final org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                    snapshot.place(snapshot.getFlags());
                    level.checkCapturedTreeStateForObserverNotify(pos, snapshot); // Paper - notify observers even if grow failed
                }
            }
        }
        return result;
    }
    // CraftBukkit end
}
