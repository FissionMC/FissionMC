package net.fissionmc.backend.mixin.world.entity.decoration;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/decoration/LeashFenceKnotEntity.java.patch (PlayerLeashEntityEvent / PlayerUnleashEntityEvent, discard cause) */
@Mixin(LeashFenceKnotEntity.class)
public abstract class LeashFenceKnotEntityMixin {
    @ModifyExpressionValue(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Leashable;canHaveALeashAttachedTo(Lnet/minecraft/world/entity/Entity;)Z", ordinal = 0))
    private boolean fission$leashEvent(final boolean canHave, @Local(name = "leashable") Leashable leashable, @Local(argsOnly = true) Player player, @Local(argsOnly = true) InteractionHand hand) {
        return canHave && org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerLeashEntityEvent(leashable, (LeashFenceKnotEntity) (Object) this, player, hand); // Paper - leash event
    }

    @ModifyExpressionValue(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Leashable;canHaveALeashAttachedTo(Lnet/minecraft/world/entity/Entity;)Z", ordinal = 1))
    private boolean fission$unleashEvent(final boolean canHave, @Local(name = "mob") Leashable mob, @Local(argsOnly = true) Player player, @Local(argsOnly = true) InteractionHand hand) {
        return canHave && org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerLeashEntityEvent(mob, player, player, hand); // Paper - leash event
    }

    @WrapOperation(method = "notifyLeasheeRemoved", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/LeashFenceKnotEntity;discard()V"))
    private void fission$discardCause(final LeashFenceKnotEntity self, final Operation<Void> original) {
        self.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DROP); // CraftBukkit - add Bukkit remove cause
    }
}
