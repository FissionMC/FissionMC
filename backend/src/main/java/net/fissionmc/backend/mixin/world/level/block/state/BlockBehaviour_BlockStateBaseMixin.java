package net.fissionmc.backend.mixin.world.level.block.state;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/state/BlockBehaviour.java.patch (BlockStateBase) */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockBehaviour_BlockStateBaseMixin {
    @Shadow protected abstract BlockState asState();
    @Shadow public abstract Block getBlock();

    private org.bukkit.craftbukkit.block.data.@org.jspecify.annotations.Nullable CraftBlockData cachedBlockData;

    public org.bukkit.craftbukkit.block.data.CraftBlockData asBlockData() {
        if (this.cachedBlockData == null) this.cachedBlockData = org.bukkit.craftbukkit.block.data.CraftBlockData.createData(this.asState());
        return (org.bukkit.craftbukkit.block.data.CraftBlockData) this.cachedBlockData.clone();
    }

    // Paper start - Protect Bedrock and End Portal/Frames from being destroyed
    public final boolean isDestroyable() {
        return this.getBlock().isDestroyable();
    }
    // Paper end - Protect Bedrock and End Portal/Frames from being destroyed

    @ModifyReturnValue(method = "getPistonPushReaction", at = @At("RETURN"))
    private PushReaction fission$pushReaction(final PushReaction original) {
        return !this.isDestroyable() ? PushReaction.IMMOVEABLE : original; // Paper - Protect Bedrock and End Portal/Frames from being destroyed
    }

    @Inject(method = "spawnAfterBreak", at = @At("TAIL"))
    private void fission$dropExperience(final ServerLevel level, final BlockPos pos, final ItemStack tool, final boolean dropExperience, final CallbackInfo ci) {
        if (dropExperience) this.getBlock().popExperience(level, pos, this.getBlock().getExpDrop(this.asState(), level, pos, tool, true), null); // Paper - Properly handle xp dropping
    }

    // TODO(behaviour): shapeExceedsCube / final-for-inlining hunks are perf-only (same results).
}
