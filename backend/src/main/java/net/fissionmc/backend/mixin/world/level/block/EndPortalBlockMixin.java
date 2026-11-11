package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.event.PortalEventResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/block/EndPortalBlock.java.patch: EntityInsideBlockEvent, EntityPortalEnterEvent (cancellable), PlayerPortalEvent/EntityPortalEvent
 * (ENDER) for the destination, END_PORTAL teleport cause, PortalCreateEvent END_PLATFORM (createEndPlatform with the entity).
 * Not ported: disableEndCredits option, the respawn reason END_PORTAL of the player respawn path, getTypeKey() for custom ends.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void fission$entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent()) { ci.cancel(); } // Paper - Add EntityInsideBlockEvent
    }

    // CraftBukkit start - Entity in portal
    @ModifyExpressionValue(method = "entityInside", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;canUsePortal(Z)Z"))
    private boolean fission$portalEnter(final boolean canUse, @Local(argsOnly = true) final Level level, @Local(argsOnly = true) final BlockPos pos, @Local(argsOnly = true) final Entity entity) {
        if (!canUse) return false;
        org.bukkit.event.entity.EntityPortalEnterEvent event = new org.bukkit.event.entity.EntityPortalEnterEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level), org.bukkit.PortalType.ENDER); // Paper - add portal type
        return event.callEvent(); // Paper - make cancellable
    }
    // CraftBukkit end

    @WrapOperation(method = "getPortalDestination", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/EndPlatformFeature;createEndPlatform(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Z)V"))
    private void fission$endPlatform(final net.minecraft.world.level.ServerLevelAccessor newLevel, final BlockPos origin, final boolean dropResources, final Operation<Void> original, @Local(argsOnly = true) final Entity entity) {
        net.minecraft.world.level.levelgen.feature.EndPlatformFeature.createEndPlatform(newLevel, origin, dropResources, entity); // CraftBukkit
    }

    // CraftBukkit start
    @WrapOperation(method = "getPortalDestination", at = @At(value = "NEW", target = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;FFLjava/util/Set;Lnet/minecraft/world/level/portal/TeleportTransition$PostTeleportTransition;)Lnet/minecraft/world/level/portal/TeleportTransition;"))
    private TeleportTransition fission$portalEvents(final ServerLevel newLevel, final Vec3 spawnPos, final Vec3 delta, final float yRot, final float xRot, final Set<Relative> relatives0, final TeleportTransition.PostTeleportTransition post, final Operation<TeleportTransition> original, @Local(argsOnly = true) final ServerLevel currentLevel, @Local(argsOnly = true) final Entity entity) {
        boolean fromEnd = currentLevel.dimension() == Level.END;
        Set<Relative> relatives = new HashSet<>(relatives0);
        relatives.removeAll(Relative.ROTATION); // remove relative rotation flags to simplify event mutation
        float absoluteYaw = !fromEnd ? yRot : entity.getYRot() + yRot;
        float absolutePitch = entity.getXRot() + xRot;
        PortalEventResult result = org.bukkit.craftbukkit.event.CraftEventFactory.handlePortalEvents(entity, org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPos, newLevel, absoluteYaw, absolutePitch), org.bukkit.PortalType.ENDER, 0, 0);
        if (result == null) {
            return null;
        }
        org.bukkit.Location to = result.to();
        return original.call(((org.bukkit.craftbukkit.CraftWorld) to.getWorld()).getHandle(), org.bukkit.craftbukkit.util.CraftLocation.toVec3(to), Vec3.ZERO, to.getYaw(), to.getPitch(), relatives, post)
            .withCause(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL);
    }
    // CraftBukkit end
}
