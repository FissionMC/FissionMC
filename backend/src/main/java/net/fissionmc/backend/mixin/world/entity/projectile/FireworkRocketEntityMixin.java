package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/projectile/FireworkRocketEntity.java.patch */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private java.util.@org.jspecify.annotations.Nullable UUID spawningEntity; // Paper

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FireworkRocketEntity;hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;"))
    private ProjectileDeflection fission$preHit(FireworkRocketEntity instance, HitResult hitResult) {
        return instance.preHitTargetOrDeflectSelf(hitResult); // CraftBukkit - projectile hit event
    }

    // Paper start - Call FireworkExplodeEvent
    @Redirect(method = {"tick", "onHitEntity", "onHitBlock"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FireworkRocketEntity;explode(Lnet/minecraft/server/level/ServerLevel;)V"))
    private void fission$explodeEvent(FireworkRocketEntity instance, ServerLevel level) {
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callFireworkExplodeEvent(instance)) {
            this.explode(level);
        }
    }
    // Paper end - Call FireworkExplodeEvent

    @org.spongepowered.asm.mixin.Shadow private void explode(ServerLevel level) { throw new AssertionError(); }

    @Redirect(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FireworkRocketEntity;discard()V"))
    private void fission$discardExplode(FireworkRocketEntity instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE); // CraftBukkit - add Bukkit remove cause
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void fission$saveSpawningEntity(ValueOutput output, CallbackInfo ci) {
        output.storeNullable("SpawningEntity", net.minecraft.core.UUIDUtil.CODEC, this.spawningEntity); // Paper
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void fission$readSpawningEntity(ValueInput input, CallbackInfo ci) {
        this.spawningEntity = input.read("SpawningEntity", net.minecraft.core.UUIDUtil.CODEC).orElse(null); // Paper
    }
}
