package net.fissionmc.backend.mixin.server.network;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FissionMC: a relayed client answers the join teleport within milliseconds. {@code handleAcceptTeleportPacket} (26.3) applies the position
 * the client reports through {@code handlePlayerPositionChange}, which measures the move from {@code firstGood*}. Those are only refreshed
 * by {@code tickPlayer()} and by the constructor; the port's join sequence moves the player AFTER the constructor ran, so a confirm that
 * wins the race against the listener's first tick sees firstGood = (0,0,0) and fails "moved too quickly!" (reproduced by the scripted
 * client: a second ClientboundPlayerPosition right after the join, the rubber banding the real client showed after the seamless switch).
 * Refreshing the good positions whenever the server teleports the player is what tickPlayer would do one tick later anyway.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplTeleportMixin {
    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("RETURN"))
    private void fission$goodPositionAfterTeleport(CallbackInfo ci) {
        ((ServerGamePacketListenerImpl) (Object) this).resetPosition();
    }
}
