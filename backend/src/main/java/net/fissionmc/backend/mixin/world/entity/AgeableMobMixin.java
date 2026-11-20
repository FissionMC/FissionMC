package net.fissionmc.backend.mixin.world.entity;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/AgeableMob.java.patch (PlayerToggleEntityAgeLockEvent when using the golden dandelion) */
@Mixin(AgeableMob.class)
public abstract class AgeableMobMixin {
    @Shadow @Final private static EntityDataAccessor<Boolean> AGE_LOCKED;

    @Shadow public abstract boolean isAgeLocked();

    @Inject(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/AgeableMob;setAgeLocked(Lnet/minecraft/world/entity/Mob;Ljava/util/function/Supplier;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)V"), cancellable = true)
    private void paper$ageLockEvent(final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir, @Local(ordinal = 0) final ItemStack itemInHand) {
        // Paper start - call PlayerToggleEntityAgeLockEvent
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerToggleEntityAgeLockEvent(player, (AgeableMob) (Object) this, itemInHand, hand, !this.isAgeLocked(), AGE_LOCKED)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
        // Paper end - call PlayerToggleEntityAgeLockEvent
    }
}
