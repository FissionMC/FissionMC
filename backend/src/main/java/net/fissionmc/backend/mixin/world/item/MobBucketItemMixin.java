package net.fissionmc.backend.mixin.world.item;

import net.fissionmc.backend.blocks.PaperBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/item/MobBucketItem.java.patch (call PlayerBucketEmptyEvent for mobs in buckets without fluids;
 * the SpawnReason.BUCKET part lives with the entity area). Fluid mob buckets reach {@link BucketItemMixin} through super.emptyContents.
 */
@Mixin(MobBucketItem.class)
public abstract class MobBucketItemMixin extends BucketItem {
    protected MobBucketItemMixin(final net.minecraft.world.level.material.Fluid content, final Properties properties) {
        super(content, properties);
    }

    @Inject(method = "emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z", at = @At("HEAD"), cancellable = true)
    private void paper$emptyEvent(final LivingEntity user, final Level level, final BlockPos pos, final BlockHitResult hitResult, final CallbackInfoReturnable<Boolean> cir) {
        if (this.content == Fluids.EMPTY && user instanceof Player player && PaperBlocks.bucketItem != null) {
            final org.bukkit.event.player.PlayerBucketEmptyEvent event = CraftEventFactory.callPlayerBucketEmptyEvent(level, player, pos, PaperBlocks.bucketClicked, PaperBlocks.bucketDirection, PaperBlocks.bucketItem, PaperBlocks.bucketHand);
            if (event.isCancelled()) {
                player.containerMenu.sendAllDataToRemote(); // SPIGOT-4541
                cir.setReturnValue(false);
                return;
            }
            PaperBlocks.itemLeftInHandAfterPlayerBucketEmptyEvent = event.getItemStack() != null
                ? (event.getItemStack().equals(org.bukkit.inventory.ItemStack.of(org.bukkit.Material.BUCKET)) ? null : CraftItemStack.asNMSCopy(event.getItemStack()))
                : ItemStack.EMPTY;
        }
    }
}
