package net.fissionmc.backend.mixin.world.entity.decoration;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/decoration/ItemFrame.java.patch */
@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
    // Done: setItem(…) delegation, damage event, PlayerItemFrameChangeEvent (place/rotate/remove).
    // TODO(behaviour): createBoundingBox should delegate to createBoundingBoxStatic; cachedMapId, push/spawnAtLocation overloads.

    // Paper start - add static method
    // Surface: public (see surface/members/entitytypes.txt). Mixin requires non-public statics; the loader widens it.
    private static AABB createBoundingBoxStatic(BlockPos blockPos, Direction direction, boolean hasFramedMap) {
        // Paper end - add static method
        float shiftToBlockWall = 0.46875F;
        Vec3 position = Vec3.atCenterOf(blockPos).relative(direction, -0.46875);
        float width = hasFramedMap ? 1.0F : 0.75F;
        float height = hasFramedMap ? 1.0F : 0.75F;
        Direction.Axis axis = direction.getAxis();
        double xSize = axis == Direction.Axis.X ? 0.0625 : width;
        double ySize = axis == Direction.Axis.Y ? 0.0625 : height;
        double zSize = axis == Direction.Axis.Z ? 0.0625 : width;
        return AABB.ofSize(position, xSize, ySize, zSize);
    }

    @Shadow @Final private static EntityDataAccessor<ItemStack> DATA_ITEM;

    @Shadow private void onItemChanged(final ItemStack item) { throw new AssertionError(); }

    // CraftBukkit start
    public void setItem(ItemStack itemStack, final boolean updateNeighbours, boolean playSound) {
        // CraftBukkit end
        final ItemFrame self = (ItemFrame) (Object) this;
        if (!itemStack.isEmpty()) {
            itemStack = itemStack.copyWithCount(1);
        }

        this.onItemChanged(itemStack);
        self.getEntityData().set(DATA_ITEM, itemStack);
        if (!itemStack.isEmpty() && updateNeighbours && playSound) { // CraftBukkit // Paper - only play sound when update flag is set
            self.playSound(self.getAddItemSound(), 1.0F, 1.0F);
        }

        if (updateNeighbours && self.getPos() != null) {
            self.level().updateNeighbourForOutputSignal(self.getPos(), Blocks.AIR);
        }
    }

    // vanilla setItem(ItemStack, boolean) delegates to the 3-arg overload with playSound = true
    @Inject(method = "setItem(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("HEAD"), cancellable = true)
    private void fission$setItemDelegate(final ItemStack itemStack, final boolean updateNeighbours, final CallbackInfo ci) {
        this.setItem(itemStack, updateNeighbours, true);
        ci.cancel();
    }

    // Damage event + PlayerItemFrameChangeEvent(REMOVE) before the item is dropped
    @Inject(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/ItemFrame;dropItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Z)V", ordinal = 0), cancellable = true)
    private void fission$hurt(final ServerLevel level, final DamageSource source, final float damage, final CallbackInfoReturnable<Boolean> cir) {
        final ItemFrame self = (ItemFrame) (Object) this;
        if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(self, source, damage, false) || self.isRemoved()) {
            cir.setReturnValue(true);
            return;
        }
        if (source.getEntity() instanceof Player player) {
            var event = new io.papermc.paper.event.player.PlayerItemFrameChangeEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.ItemFrame) self.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(self.getItem()), io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE);
            if (!event.callEvent()) {
                cir.setReturnValue(true); // return true here because you aren't cancelling the damage, just the change
                return;
            }
            self.setItem(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItemStack()), false);
        }
    }

    private ItemStack fission$placeItem;

    // PlayerItemFrameChangeEvent(PLACE)
    @Inject(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/ItemFrame;setItem(Lnet/minecraft/world/item/ItemStack;)V", ordinal = 0), cancellable = true)
    private void fission$place(final Player player, final InteractionHand hand, final Vec3 location, final CallbackInfoReturnable<InteractionResult> cir) {
        final ItemFrame self = (ItemFrame) (Object) this;
        ItemStack itemStack = player.getItemInHand(hand);
        io.papermc.paper.event.player.PlayerItemFrameChangeEvent event = new io.papermc.paper.event.player.PlayerItemFrameChangeEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.ItemFrame) self.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(itemStack), io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE);
        if (!event.callEvent()) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        this.fission$placeItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItemStack());
    }

    @ModifyArg(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/ItemFrame;setItem(Lnet/minecraft/world/item/ItemStack;)V", ordinal = 0), index = 0)
    private ItemStack fission$placeItemArg(final ItemStack original) {
        ItemStack replaced = this.fission$placeItem;
        this.fission$placeItem = null;
        return replaced != null ? replaced : original;
    }

    // PlayerItemFrameChangeEvent(ROTATE)
    @Inject(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/ItemFrame;getRotateItemSound()Lnet/minecraft/sounds/SoundEvent;", ordinal = 0), cancellable = true)
    private void fission$rotate(final Player player, final InteractionHand hand, final Vec3 location, final CallbackInfoReturnable<InteractionResult> cir) {
        final ItemFrame self = (ItemFrame) (Object) this;
        io.papermc.paper.event.player.PlayerItemFrameChangeEvent event = new io.papermc.paper.event.player.PlayerItemFrameChangeEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.ItemFrame) self.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(self.getItem()), io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction.ROTATE);
        if (!event.callEvent()) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        this.setItem(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItemStack()), false, false);
    }
}
