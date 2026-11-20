package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/BoatItem.java.patch (PlayerInteractEvent for the boat placement, EntityPlaceEvent before the boat is added) */
@Mixin(BoatItem.class)
public abstract class BoatItemMixin {
    @Inject(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/BoatItem;getBoat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;"), cancellable = true)
    private void paper$boatInteract(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir, @Local(ordinal = 0) final ItemStack itemStack, @Local(ordinal = 0) final HitResult hitResult) {
        // CraftBukkit start - Boat placement
        final BlockHitResult blockHit = (BlockHitResult) hitResult;
        org.bukkit.event.player.PlayerInteractEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, blockHit.getBlockPos(), blockHit.getDirection(), itemStack, false, hand, blockHit.getLocation());
        if (event.isCancelled()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
        // CraftBukkit end
    }

    @Inject(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), cancellable = true)
    private void paper$boatPlace(final Level level, final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir, @Local(ordinal = 0) final HitResult hitResult, @Local final AbstractBoat boat) {
        // CraftBukkit start
        final BlockHitResult blockHit = (BlockHitResult) hitResult;
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPlaceEvent(level, blockHit.getBlockPos(), player.getDirection(), player, boat, hand).isCancelled()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
        // CraftBukkit end
    }
}
