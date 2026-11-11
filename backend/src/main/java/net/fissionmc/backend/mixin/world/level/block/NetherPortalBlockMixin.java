package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.bukkit.craftbukkit.event.PortalEventResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/block/NetherPortalBlock.java.patch: EntityInsideBlockEvent, EntityPortalEnterEvent (cancellable), EntityPortalReadyEvent,
 * PlayerPortalEvent/EntityPortalEvent (CraftEventFactory.handlePortalEvents: destination, search radius, create radius, canCreatePortal), NETHER_PORTAL teleport cause.
 * Not ported: zombified piglin spawn reason NETHER_PORTAL / nerf options / spigot zombie-pigmen option (randomTick), getTypeKey() for custom nether dimensions,
 * Paper's portal search radius options (the config values are used as is for the event).
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
    private static final ThreadLocal<PortalEventResult> fission$result = new ThreadLocal<>();

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void fission$entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent()) { ci.cancel(); } // Paper - Add EntityInsideBlockEvent
    }

    // CraftBukkit start - Entity in portal
    @ModifyExpressionValue(method = "entityInside", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;canUsePortal(Z)Z"))
    private boolean fission$portalEnter(final boolean canUse, @Local(argsOnly = true) final Level level, @Local(argsOnly = true) final BlockPos pos, @Local(argsOnly = true) final Entity entity) {
        if (!canUse) return false;
        org.bukkit.event.entity.EntityPortalEnterEvent event = new org.bukkit.event.entity.EntityPortalEnterEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level), org.bukkit.PortalType.NETHER); // Paper - add portal type
        return event.callEvent(); // Paper - make cancellable
    }
    // CraftBukkit end

    // Paper start - Add EntityPortalReadyEvent
    @ModifyExpressionValue(method = "getPortalDestination", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel fission$portalReady(final ServerLevel newLevel, @Local(argsOnly = true) final Entity entity) {
        io.papermc.paper.event.entity.EntityPortalReadyEvent portalReadyEvent = new io.papermc.paper.event.entity.EntityPortalReadyEvent(entity.getBukkitEntity(), newLevel == null ? null : newLevel.getWorld(), org.bukkit.PortalType.NETHER);
        if (!portalReadyEvent.callEvent()) {
            entity.portalProcess = null;
            return null;
        }
        return portalReadyEvent.getTargetWorld() == null ? null : ((org.bukkit.craftbukkit.CraftWorld) portalReadyEvent.getTargetWorld()).getHandle();
    }
    // Paper end - Add EntityPortalReadyEvent

    // CraftBukkit start
    @WrapOperation(method = "getPortalDestination", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/NetherPortalBlock;getExitPortal(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/level/border/WorldBorder;)Lnet/minecraft/world/level/portal/TeleportTransition;"))
    private TeleportTransition fission$portalEvents(final NetherPortalBlock self, ServerLevel newLevel, final Entity entity, final BlockPos portalEntryPos, BlockPos approximateExitPos, final boolean toNether, WorldBorder newWorldBorder, final Operation<TeleportTransition> original) {
        int portalSearchRadius = toNether ? 16 : 128; // vanilla values (Paper: configurable)
        PortalEventResult result = org.bukkit.craftbukkit.event.CraftEventFactory.handlePortalEvents(entity, org.bukkit.craftbukkit.util.CraftLocation.toBukkit(approximateExitPos, newLevel), org.bukkit.PortalType.NETHER, portalSearchRadius, 16);
        if (result == null) {
            return null;
        }
        newLevel = ((org.bukkit.craftbukkit.CraftWorld) result.to().getWorld()).getHandle();
        newWorldBorder = newLevel.getWorldBorder();
        approximateExitPos = newWorldBorder.clampToBounds(result.to().getX(), result.to().getY(), result.to().getZ());
        fission$result.set(result);
        try {
            return original.call(self, newLevel, entity, portalEntryPos, approximateExitPos, toNether, newWorldBorder);
        } finally {
            fission$result.remove();
        }
    }

    @WrapOperation(method = "getExitPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/portal/PortalForcer;findClosestPortalPosition(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/level/border/WorldBorder;)Ljava/util/Optional;"))
    private Optional<BlockPos> fission$searchRadius(final PortalForcer forcer, final BlockPos approximateExitPos, final boolean toNether, final WorldBorder worldBorder, final Operation<Optional<BlockPos>> original) {
        PortalEventResult result = fission$result.get();
        return result == null ? original.call(forcer, approximateExitPos, toNether, worldBorder) : forcer.findClosestPortalPosition(approximateExitPos, worldBorder, result.searchRadius());
    }

    @WrapOperation(method = "getExitPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/portal/PortalForcer;createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;"))
    private Optional<BlockUtil.FoundRectangle> fission$createRadius(final PortalForcer forcer, final BlockPos origin, final Direction.Axis axis, final Operation<Optional<BlockUtil.FoundRectangle>> original, @Local(argsOnly = true) final Entity entity) {
        PortalEventResult result = fission$result.get();
        if (result == null) return original.call(forcer, origin, axis);
        if (!result.canCreatePortal()) return Optional.empty(); // CraftBukkit: Paper returns null without creating
        return forcer.createPortal(origin, axis, entity, result.createRadius());
    }

    @ModifyReturnValue(method = "getDimensionTransitionFromExit", at = @At("RETURN"))
    private static TeleportTransition fission$cause(final TeleportTransition transition) {
        return transition.withCause(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL); // CraftBukkit
    }
    // CraftBukkit end
}
