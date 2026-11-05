package net.fissionmc.backend.mixin.core.dispenser;

import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/core/dispenser/ProjectileDispenseBehavior.java.patch
 * PARTIAL: fires BlockDispenseEvent up front (cancel + item-change rerouting honoured). TODO(behaviour): item/velocity changes made by the event
 * are not applied to the spawned entity (Paper uses event.getItem()/getVelocity() inside the body).
 */
@Mixin(ProjectileDispenseBehavior.class)
public abstract class ProjectileDispenseBehaviorMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void fission(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, source.pos().relative(source.state().getValue(DispenserBlock.FACING)), dispensed, (net.minecraft.core.dispenser.DispenseItemBehavior) (Object) this);
        if (result != null) cir.setReturnValue(result);
    }
}
