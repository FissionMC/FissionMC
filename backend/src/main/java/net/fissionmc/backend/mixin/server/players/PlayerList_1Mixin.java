package net.fissionmc.backend.mixin.server.players;

import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Paper: sources/net/minecraft/server/players/PlayerList.java.patch (world border listener in addWorldborderListener) */
@Mixin(targets = "net.minecraft.server.players.PlayerList$1")
public abstract class PlayerList_1Mixin {
    @Redirect(method = {"onSetSize", "onLerpSize", "onSetCenter", "onSetWarningTime", "onSetWarningBlocks"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V"))
    private void paper$broadcastWorldborder(final PlayerList playerList, final Packet<?> packet, final ResourceKey<Level> dimension) {
        playerList.fission$broadcastWorldborder(packet, dimension); // Paper - virtual world border API
    }
}
