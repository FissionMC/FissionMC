package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/EndGatewayBlock.java.patch: EntityInsideBlockEvent, EntityPortalEnterEvent (END_GATEWAY, cancellable), END_GATEWAY teleport cause. */
@Mixin(EndGatewayBlock.class)
public abstract class EndGatewayBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void fission$entityInside(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise, final CallbackInfo ci) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent()) { ci.cancel(); } // Paper - Add EntityInsideBlockEvent
    }

    // Paper start - call EntityPortalEnterEvent
    @Inject(method = "entityInside", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setAsInsidePortal(Lnet/minecraft/world/level/block/Portal;Lnet/minecraft/core/BlockPos;)V"), cancellable = true)
    private void fission$portalEnter(final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise, final CallbackInfo ci) {
        org.bukkit.event.entity.EntityPortalEnterEvent event = new org.bukkit.event.entity.EntityPortalEnterEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level), org.bukkit.PortalType.END_GATEWAY); // Paper - add portal type
        if (!event.callEvent()) ci.cancel();
    }
    // Paper end - call EntityPortalEnterEvent

    @ModifyReturnValue(method = "getPortalDestination", at = @At("RETURN"))
    private TeleportTransition fission$cause(final TeleportTransition transition) {
        return transition == null ? null : transition.withCause(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_GATEWAY); // CraftBukkit
    }
}
