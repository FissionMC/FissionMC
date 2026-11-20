package net.fissionmc.backend.mixin.core.dispenser;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.fissionmc.backend.blocks.PaperDispense;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: DispenseItemBehavior.java.patch - BlockDispenseArmorEvent for the brush on armadillos. */
@Mixin(targets = "net.minecraft.core.dispenser.DispenseItemBehavior$11")
public abstract class DispenseItemBehavior_11Mixin {
    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", ordinal = 0), cancellable = true)
    private void paper$dispenseEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir, @Local final List<Entity> armadillos) {
        // CraftBukkit start
        final org.bukkit.block.Block block = CraftBlock.at(source.level(), source.pos());
        final org.bukkit.inventory.ItemStack craftItem = CraftItemStack.asBukkitMirror(dispensed); // Paper - ignore stack size on damageable items
        final BlockDispenseEvent event = new BlockDispenseArmorEvent(block, craftItem.clone(), (org.bukkit.entity.LivingEntity) armadillos.get(0).getBukkitEntity());
        source.level().getCraftServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            PaperDispense.fail(this);
            cir.setReturnValue(dispensed);
            return;
        }
        if (!event.getItem().equals(craftItem)) {
            // Chain to handler for new item
            final ItemStack eventStack = CraftItemStack.asNMSCopy(event.getItem());
            final DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
            if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != (Object) this) {
                dispenseBehavior.dispense(source, eventStack);
                cir.setReturnValue(dispensed);
            }
        }
        // CraftBukkit end
    }
}
