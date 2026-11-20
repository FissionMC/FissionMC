package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: LecternBlock.java.patch - BlockRedstoneEvent for the page-change signal and PlayerInsertLecternBookEvent.
 * TODO(behaviour): a book replaced by an event handler is not applied (only cancellation); the InventoryOpenEvent cancellation fix in useWithoutItem.
 */
@Mixin(LecternBlock.class)
public abstract class LecternBlockMixin {
    @Inject(method = "changePowered", at = @At("HEAD"), cancellable = true)
    private static void paper$redstoneEvent(final Level level, final BlockPos pos, final BlockState state, final boolean isPowered, final CallbackInfo ci) {
        if (state.getValue(LecternBlock.POWERED) != isPowered && !CraftEventFactory.callBinaryRedstoneChange(level, pos, isPowered)) { // Paper - Call BlockRedstoneEvent
            ci.cancel();
        }
    }

    @Inject(method = "placeBook", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;consumeAndReturn(ILnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"), cancellable = true)
    private static void paper$insertBookEvent(final LivingEntity sourceEntity, final Level level, final BlockPos pos, final BlockState state, final ItemStack book, final CallbackInfo ci) {
        if (sourceEntity instanceof ServerPlayer serverPlayer) {
            final io.papermc.paper.event.player.PlayerInsertLecternBookEvent event = new io.papermc.paper.event.player.PlayerInsertLecternBookEvent(
                serverPlayer.getBukkitEntity(), CraftBlock.at(level, pos), CraftItemStack.asBukkitMirror(book.copyWithCount(1)));
            if (!event.callEvent()) {
                ci.cancel();
            }
        }
    }
}
