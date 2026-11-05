package net.fissionmc.backend.mixin.server.network;

import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/network/ServerConnectionListener.java.patch (channel initializer in startTcpServerListener) */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public abstract class ServerConnectionListener_1Mixin {
    @Inject(method = "initChannel", at = @At("TAIL"), remap = false)
    private void paper$channelInitializeListeners(final Channel channel, final CallbackInfo ci) {
        io.papermc.paper.network.ChannelInitializeListenerHolder.callListeners(channel); // Paper - Add Channel initialization listeners
    }
    // TODO(behaviour): Paper - Add support for Proxy Protocol (config: proxies.proxy-protocol, default false)
}
