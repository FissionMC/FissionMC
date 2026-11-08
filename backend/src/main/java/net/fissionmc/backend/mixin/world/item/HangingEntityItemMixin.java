package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CraftBukkit/Paper: sources/net/minecraft/world/item/HangingEntityItem.java.patch (fire HangingPlaceEvent for paintings and item frames). */
@Mixin(HangingEntityItem.class)
public abstract class HangingEntityItemMixin {
    @Inject(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/HangingEntity;playPlacementSound()V"), cancellable = true)
    private void fission$hangingPlaceEvent(final UseOnContext context, final CallbackInfoReturnable<InteractionResult> cir,
                                           @Local(ordinal = 1) final BlockPos blockPos, @Local final Direction clickedFace, @Local final Player player,
                                           @Local final ItemStack itemInHand, @Local final HangingEntity entity, @Local final Level level) {
        // CraftBukkit start - fire HangingPlaceEvent
        final org.bukkit.entity.Player bukkitPlayer = player == null ? null : (org.bukkit.entity.Player) player.getBukkitEntity();
        final org.bukkit.block.Block blockClicked = org.bukkit.craftbukkit.block.CraftBlock.at(level, blockPos);
        final org.bukkit.block.BlockFace blockFace = org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(clickedFace);
        final org.bukkit.inventory.EquipmentSlot hand = org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(context.getHand());

        final org.bukkit.event.hanging.HangingPlaceEvent event = new org.bukkit.event.hanging.HangingPlaceEvent((org.bukkit.entity.Hanging) entity.getBukkitEntity(), bukkitPlayer, blockClicked, blockFace, hand, org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(itemInHand));
        level.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            if (player != null) player.containerMenu.sendAllDataToRemote(); // Paper - Fix inventory desync
            cir.setReturnValue(InteractionResult.FAIL);
        }
        // CraftBukkit end
    }
}
