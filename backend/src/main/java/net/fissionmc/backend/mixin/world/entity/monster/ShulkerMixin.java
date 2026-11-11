package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * Paper: sources/net/minecraft/world/entity/monster/Shulker.java.patch
 * done: ShulkerDuplicateEvent, BREEDING spawn reason of the duplicate. Not ported: EntityTeleportEvent in teleportSomewhere.
 */
@Mixin(Shulker.class)
public abstract class ShulkerMixin {
    @WrapOperation(method = "hitByShulkerBullet", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean paper$duplicateEvent(final net.minecraft.world.level.Level level, final net.minecraft.world.entity.Entity baby, final Operation<Boolean> original) {
        // Paper start - Call ShulkerDuplicateEvent
        if (!new io.papermc.paper.event.entity.ShulkerDuplicateEvent((org.bukkit.entity.Shulker) baby.getBukkitEntity(), (org.bukkit.entity.Shulker) ((Shulker) (Object) this).getBukkitEntity()).callEvent()) {
            return false;
        }
        // Paper end - Call ShulkerDuplicateEvent
        return level.addFreshEntity(baby, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BREEDING); // CraftBukkit - the mysteries of life
    }
}
