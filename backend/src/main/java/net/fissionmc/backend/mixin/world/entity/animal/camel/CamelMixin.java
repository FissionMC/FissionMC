package net.fissionmc.backend.mixin.world.entity.animal.camel;

import net.minecraft.world.entity.animal.camel.Camel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** Paper: sources/net/minecraft/world/entity/animal/camel/Camel.java.patch (done: EntityToggleSitEvent in sitDown / standUp / standUpInstantly; other hunks not ported). */
@Mixin(Camel.class)
public abstract class CamelMixin {
    @Inject(method = "sitDown", at = @At("HEAD"), cancellable = true)
    private void paper$sitDown(final CallbackInfo ci) {
        final Camel self = (Camel) (Object) this;
        if (!self.isCamelSitting() && !new io.papermc.paper.event.entity.EntityToggleSitEvent(self.getBukkitEntity(), true).callEvent()) ci.cancel(); // Paper - Add EntityToggleSitEvent
    }

    @Inject(method = {"standUp", "standUpInstantly"}, at = @At("HEAD"), cancellable = true)
    private void paper$standUp(final CallbackInfo ci) {
        final Camel self = (Camel) (Object) this;
        if (self.isCamelSitting() && !new io.papermc.paper.event.entity.EntityToggleSitEvent(self.getBukkitEntity(), false).callEvent()) ci.cancel(); // Paper - Add EntityToggleSitEvent
    }
}
