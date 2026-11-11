package net.fissionmc.backend.mixin.core.dispenser;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.dispenser.EquipmentDispenseItemBehavior;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/core/dispenser/EquipmentDispenseItemBehavior.java.patch (BlockDispenseArmorEvent, chaining to the behaviour of a replaced item).
 * FissionMC: the vanilla split/setItemSlot only runs when the event left the item unchanged; a changed item is equipped here without shrinking, like Paper.
 */
@Mixin(EquipmentDispenseItemBehavior.class)
public abstract class EquipmentDispenseItemBehaviorMixin {
    private static final ThreadLocal<DispenseItemBehavior> fission$current = new ThreadLocal<>();

    // Paper start - fix possible StackOverflowError
    private static boolean dispenseEquipment(final BlockSource source, final ItemStack dispensed, final DispenseItemBehavior currentBehavior) {
        DispenseItemBehavior previous = fission$current.get();
        fission$current.set(currentBehavior);
        try {
            return EquipmentDispenseItemBehavior.dispenseEquipment(source, dispensed);
        } finally {
            fission$current.set(previous);
        }
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/dispenser/EquipmentDispenseItemBehavior;dispenseEquipment(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean fission$executeCurrent(final BlockSource source, final ItemStack dispensed, final Operation<Boolean> original) {
        return dispenseEquipment(source, dispensed, (DispenseItemBehavior) (Object) this);
    }
    // Paper end

    // CraftBukkit start
    @Inject(method = "dispenseEquipment(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;split(I)Lnet/minecraft/world/item/ItemStack;"), cancellable = true)
    private static void fission$armorEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<Boolean> cir, @Local(ordinal = 0) final LivingEntity target, @Local(ordinal = 0) final EquipmentSlot slot) {
        ItemStack equip = dispensed.copyWithCount(1); // Paper - shrink below and single item in event
        net.minecraft.world.level.Level world = source.level();
        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(world, source.pos());
        org.bukkit.inventory.ItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(equip);

        org.bukkit.event.block.BlockDispenseArmorEvent event = new org.bukkit.event.block.BlockDispenseArmorEvent(block, craftItem.clone(), target.getBukkitEntity());
        world.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            cir.setReturnValue(false);
            return;
        }

        if (!event.getItem().equals(craftItem)) {
            // Chain to handler for new item
            ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
            DispenseItemBehavior dispenseItemBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
            DispenseItemBehavior currentBehavior = fission$current.get();
            if (dispenseItemBehavior != DispenseItemBehavior.NOOP && (currentBehavior == null || dispenseItemBehavior != currentBehavior)) {
                dispenseItemBehavior.dispense(source, eventStack);
                cir.setReturnValue(true);
                return;
            }
            target.setItemSlot(slot, eventStack); // no shrink: the dispensed stack is not the item that was equipped
            if (target instanceof Mob targetMob) {
                targetMob.setGuaranteedDrop(slot);
                targetMob.setPersistenceRequired();
            }
            cir.setReturnValue(true);
        }
    }
    // CraftBukkit end
}
