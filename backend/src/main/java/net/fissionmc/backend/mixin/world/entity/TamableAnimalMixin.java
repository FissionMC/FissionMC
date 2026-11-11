package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * Paper: sources/net/minecraft/world/entity/TamableAnimal.java.patch
 * done: EntityToggleSitEvent (not while loading), TameableDeathMessageEvent, EntityTeleportEvent of the teleport-to-owner.
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin {
    @Unique private boolean paper$loading; // Paper calls setInSittingPose(value, false) from readAdditionalSaveData
    @Unique private boolean paper$teleportCancelled;

    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void paper$loadStart(final CallbackInfo ci) {
        this.paper$loading = true;
    }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void paper$loadEnd(final CallbackInfo ci) {
        this.paper$loading = false;
    }

    // Paper start - Add EntityToggleSitEvent
    @Inject(method = "setInSittingPose", at = @At("HEAD"), cancellable = true)
    private void paper$toggleSit(final boolean value, final CallbackInfo ci) {
        if (!this.paper$loading && !new io.papermc.paper.event.entity.EntityToggleSitEvent(((TamableAnimal) (Object) this).getBukkitEntity(), value).callEvent()) ci.cancel();
    }
    // Paper end - Add EntityToggleSitEvent

    // Paper start - Add TameableDeathMessageEvent
    @WrapOperation(method = "die", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V"))
    private void paper$deathMessage(final net.minecraft.server.level.ServerPlayer serverPlayer, final net.minecraft.network.chat.Component message, final Operation<Void> original) {
        io.papermc.paper.event.entity.TameableDeathMessageEvent event = new io.papermc.paper.event.entity.TameableDeathMessageEvent((org.bukkit.entity.Tameable) ((TamableAnimal) (Object) this).getBukkitEntity(), io.papermc.paper.adventure.PaperAdventure.asAdventure(message));
        if (event.callEvent()) {
            original.call(serverPlayer, io.papermc.paper.adventure.PaperAdventure.asVanilla(event.deathMessage()));
        }
    }
    // Paper end - Add TameableDeathMessageEvent

    // CraftBukkit start - EntityTeleportEvent (a cancelled event makes maybeTeleportTo return false)
    @WrapOperation(method = "maybeTeleportTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/TamableAnimal;snapTo(DDDFF)V"))
    private void paper$teleportEvent(final TamableAnimal self, final double x, final double y, final double z, final float yRot, final float xRot, final Operation<Void> original) {
        org.bukkit.event.entity.EntityTeleportEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTeleportEvent(self, x, y, z);
        if (event.isCancelled() || event.getTo() == null) {
            this.paper$teleportCancelled = true;
            return;
        }
        org.bukkit.Location to = event.getTo();
        original.call(self, to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch());
    }

    @Inject(method = "maybeTeleportTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/TamableAnimal;snapTo(DDDFF)V", shift = At.Shift.AFTER), cancellable = true)
    private void paper$teleportCancelled(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (this.paper$teleportCancelled) {
            this.paper$teleportCancelled = false;
            cir.setReturnValue(false);
        }
    }
    // CraftBukkit end
}
