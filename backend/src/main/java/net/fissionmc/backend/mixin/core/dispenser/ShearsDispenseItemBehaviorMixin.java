package net.fissionmc.backend.mixin.core.dispenser;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.blocks.PaperDispense;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.ShearsDispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: ShearsDispenseItemBehavior.java.patch - BlockDispenseEvent at the start and BlockShearEntityEvent per sheared entity.
 * TODO(behaviour): the event's drops list is not applied (vanilla Shearable has no shear(..., drops) overload here).
 */
@Mixin(ShearsDispenseItemBehavior.class)
public abstract class ShearsDispenseItemBehaviorMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void paper$dispenseEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        PaperDispense.shearSource = source;
        final ItemStack result = PaperDispense.fire(source, dispensed, this);
        if (result != null) {
            PaperDispense.fail(this);
            cir.setReturnValue(result);
        }
    }

    // Paper - Add drops to shear events: BlockShearEntityEvent; a cancelled event skips this entity (Paper `continue`s)
    @WrapOperation(method = "tryShearEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Shearable;readyForShearing()Z"))
    private static boolean paper$shearEvent(final Shearable shearable, final Operation<Boolean> original, @Local(argsOnly = true) final ServerLevel level, @Local(argsOnly = true) final ItemStack tool) {
        if (!original.call(shearable)) {
            return false;
        }
        final BlockSource source = PaperDispense.shearSource;
        if (source == null) {
            return true;
        }
        final org.bukkit.block.Block bukkitBlock = CraftBlock.at(level, source.pos());
        return !CraftEventFactory.callBlockShearEntityEvent((Entity) shearable, bukkitBlock, CraftItemStack.asBukkitMirror(tool), new java.util.ArrayList<>()).isCancelled();
    }
}
