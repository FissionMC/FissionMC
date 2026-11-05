package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.fissionmc.backend.blocks.PaperBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/BlockItem.java.patch */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Shadow protected abstract boolean mustSurvive();

    // Paper - Reset placed block on exception: snapshot of the clicked block before placement
    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$capturePreviousState(final BlockItem self, final BlockPlaceContext updatedPlaceContext, final BlockState placementState, final Operation<Boolean> original,
                                                 @Share("previousState") final LocalRef<org.bukkit.block.BlockState> previousState) {
        previousState.set(org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(updatedPlaceContext.getLevel(), updatedPlaceContext.getClickedPos())); // Paper - Reset placed block on exception
        return original.call(self, updatedPlaceContext, placementState);
    }

    // CraftBukkit start - special case for handling block placement with water lilies, frog spawn and snow buckets
    @Inject(method = "place", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/world/level/block/Block;setPlacedBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"))
    private void fission$placeEvent(final BlockPlaceContext context, final CallbackInfoReturnable<InteractionResult> cir,
                                    @Local(ordinal = 1) final BlockPlaceContext updatedPlaceContext, @Local final BlockPos pos, @Local final Level level, @Local final Player player,
                                    @Share("previousState") final LocalRef<org.bukkit.block.BlockState> previousState) {
        if (player != null && ((Object) this instanceof PlaceOnWaterBlockItem || (Object) this instanceof SolidBucketItem)) {
            org.bukkit.event.block.BlockPlaceEvent placeEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockPlaceEvent((net.minecraft.server.level.ServerLevel) level, player, updatedPlaceContext.getHand(), previousState.get(), pos);
            if (placeEvent.isCancelled() || !placeEvent.canBuild()) {
                ((org.bukkit.craftbukkit.block.CraftBlockState) previousState.get()).revertPlace(false);

                player.containerMenu.forceHeldSlot(updatedPlaceContext.getHand());
                cir.setReturnValue(InteractionResult.FAIL);
            }
        }
    }
    // CraftBukkit end

    // Paper - Fix block place logic; reintroduce this for the dispenser (i.e the shulker): ItemStack#useOn plays it for players
    @WrapWithCondition(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private boolean fission$placeSound(final Level level, final Entity player, final BlockPos pos, final SoundEvent sound, final SoundSource source, final float volume, final float pitch) {
        return player == null;
    }

    // Paper - track placed block position from block item
    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V"))
    private void fission$trackPlacedPos(final Level level, final Holder<GameEvent> event, final BlockPos pos, final GameEvent.Context context, final Operation<Void> original) {
        PaperBlocks.placedPos = pos.immutable(); // Paper - track placed block position from block item
        original.call(level, event, pos, context);
    }

    // CraftBukkit start (replaced body)
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void fission$canPlace(final BlockPlaceContext context, final BlockState stateForPlacement, final CallbackInfoReturnable<Boolean> cir) {
        if (context.getLevel().isClientSide()) {
            return;
        }
        Player player = context.getPlayer();
        Level world = context.getLevel(); // Paper - Cancel hit for vanished players
        boolean canBuild = (!this.mustSurvive() || stateForPlacement.canSurvive(world, context.getClickedPos()))
            && world.checkEntityCollision(stateForPlacement, player, CollisionContext.placementContext(player), context.getClickedPos(), true); // Paper - Cancel hit for vanished players
        org.bukkit.entity.Player bukkitPlayer = (context.getPlayer() instanceof ServerPlayer) ? (org.bukkit.entity.Player) context.getPlayer().getBukkitEntity() : null;

        org.bukkit.event.block.BlockCanBuildEvent event = new org.bukkit.event.block.BlockCanBuildEvent(
            org.bukkit.craftbukkit.block.CraftBlock.at(world, context.getClickedPos()), bukkitPlayer,
            stateForPlacement.asBlockData(), canBuild, org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(context.getHand())
        );
        world.getCraftServer().getPluginManager().callEvent(event);

        cir.setReturnValue(event.isBuildable());
        // CraftBukkit end
    }

    // Spigot - add permission
    @WrapOperation(method = "updateCustomBlockEntityTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;canUseGameMasterBlocks()Z"))
    private static boolean fission$nbtPlacePermission(final Player player, final Operation<Boolean> original) {
        return original.call(player) || (player.getAbilities().instabuild && player.getBukkitEntity().hasPermission("minecraft.nbt.place")); // Spigot - add permission
    }
}
