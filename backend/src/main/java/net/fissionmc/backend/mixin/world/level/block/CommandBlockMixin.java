package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.redstone.Orientation;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/CommandBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(CommandBlock.class)
public abstract class CommandBlockMixin {
    @Inject(method = "setPoweredAndUpdate", cancellable = true, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/CommandBlockEntity;setPowered(Z)V"))
    private void fission$redstone(final Level level, final BlockPos pos, final net.minecraft.world.level.block.entity.CommandBlockEntity commandBlock, final boolean isPowered, final CallbackInfo ci) {
        // Paper start - Call BlockRedstoneEvent
        if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, isPowered)) ci.cancel();
        // Paper end - Call BlockRedstoneEvent
    }

    @WrapOperation(method = "useWithoutItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;canUseGameMasterBlocks()Z"))
    private boolean fission$permission(final Player player, final Operation<Boolean> original) {
        return original.call(player) || (player.isCreative() && player.getBukkitEntity().hasPermission("minecraft.commandblock")); // Paper - command block permission
    }
}
