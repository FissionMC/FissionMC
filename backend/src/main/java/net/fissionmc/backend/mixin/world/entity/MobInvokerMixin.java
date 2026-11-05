package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokers for protected Mob methods used by the replaced Entity#interact body in {@link EntityMixin}. */
@Mixin(Mob.class)
public interface MobInvokerMixin {
    @Invoker("canShearEquipment")
    boolean fission$canShearEquipment(Player player);

    @Invoker("attemptToShearEquipment")
    boolean fission$attemptToShearEquipment(Player player, InteractionHand hand, ItemStack heldItem);
}
