package net.fissionmc.backend.mixin.world.entity.animal.fox;

import net.minecraft.world.entity.animal.fox.Fox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** Paper: sources/net/minecraft/world/entity/animal/fox/Fox.java.patch (done: EntityToggleSitEvent in setSitting, not while loading; other hunks not ported). */
@Mixin(Fox.class)
public abstract class FoxMixin {
    @Unique private boolean paper$loading; // Paper calls setSitting(value, false) from readAdditionalSaveData

    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void paper$loadStart(final CallbackInfo ci) {
        this.paper$loading = true;
    }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void paper$loadEnd(final CallbackInfo ci) {
        this.paper$loading = false;
    }

    @Inject(method = "setSitting", at = @At("HEAD"), cancellable = true)
    private void paper$toggleSit(final boolean value, final CallbackInfo ci) {
        if (!this.paper$loading && !new io.papermc.paper.event.entity.EntityToggleSitEvent(((Fox) (Object) this).getBukkitEntity(), value).callEvent()) ci.cancel(); // Paper - Add EntityToggleSitEvent
    }
}
