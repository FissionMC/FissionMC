package net.fissionmc.backend.mixin.server.network;

import java.util.Optional;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/network/ServerCommonPacketListenerImpl.java.patch */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Shadow @Final protected MinecraftServer server;
    @Shadow @Final protected Connection connection; // Surface: public (Paper)
    @Shadow @Final private boolean transferred;

    @Shadow public abstract void disconnect(DisconnectionDetails details);

    // Surface: public (final) fields. Mixin requires private, non-final-assigned-outside-<init> fields; the loader widens them.
    // CraftBukkit start
    private org.bukkit.craftbukkit.CraftServer cserver;
    // CraftBukkit end
    private java.util.Map<java.util.UUID, net.kyori.adventure.resource.ResourcePackCallback> packCallbacks = new java.util.concurrent.ConcurrentHashMap<>(); // Paper - adventure resource pack callbacks // TODO(behaviour): consumed in handleResourcePackResponse
    private static final long KEEPALIVE_LIMIT = Long.getLong("paper.playerconnection.keepalive", 30) * 1000; // Paper - provide property to set keepalive limit // TODO(behaviour): used in keepConnectionAlive
    private static final net.minecraft.resources.Identifier MINECRAFT_BRAND = net.minecraft.resources.Identifier.withDefaultNamespace("brand"); // Paper - Brand support
    // Paper start - retain certain values
    private @Nullable String clientBrand;
    private java.util.Set<String> pluginMessagerChannels;
    // Paper end - retain certain values

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$init(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie, final CallbackInfo ci) {
        // Paper start
        this.clientBrand = cookie.clientBrand();
        this.cserver = server.server;
        this.pluginMessagerChannels = cookie.channels();
        // Paper end
    }

    // Paper: createCookie passes clientBrand and pluginMessagerChannels to the new cookie (record components; see CommonListenerCookieMixin)
    @Inject(method = "createCookie", at = @At("RETURN"))
    private void fission$createCookie(final ClientInformation clientInformation, final CallbackInfoReturnable<CommonListenerCookie> cir) {
        CommonListenerCookie cookie = cir.getReturnValue();
        cir.setReturnValue(CommonListenerCookie.create(cookie.gameProfile(), cookie.latency(), cookie.clientInformation(), cookie.transferred(), this.clientBrand, this.pluginMessagerChannels)); // Paper
    }

    // Paper start - configuration phase API
    // Paper: abstract; overridden in ServerGamePacketListenerImplMixin / ServerConfigurationPacketListenerImplMixin.
    public io.papermc.paper.connection.PlayerCommonConnection getApiConnection() {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }

    public net.kyori.adventure.audience.Audience getAudience() {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }
    // Paper end - configuration phase API

    // Paper start
    private static final net.minecraft.resources.Identifier CUSTOM_REGISTER = net.minecraft.resources.Identifier.withDefaultNamespace("register");
    private static final net.minecraft.resources.Identifier CUSTOM_UNREGISTER = net.minecraft.resources.Identifier.withDefaultNamespace("unregister");
    // Paper end
    // TODO(behaviour): handleCustomPayload (channel register/unregister, brand, Messenger dispatch), handleCustomClickAction,
    // handleResourcePackResponse callbacks, handleCookieResponse, keepalive limit, send() compass target, disconnect(DisconnectionDetails) kick event

    public void disconnect(Component reason, io.papermc.paper.connection.DisconnectionReason cause) {
        this.disconnect(DisconnectionDetails.create(reason, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(cause)));
        // Paper end - kick event causes
    }

    // Paper start - add proper async disconnect
    public final void disconnectAsync(Component component, io.papermc.paper.connection.DisconnectionReason reason) {
        this.disconnectAsync(DisconnectionDetails.create(component, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason)));
    }

    @Deprecated // Paper: also @io.papermc.paper.annotation.DoNotUse
    public final void disconnectAsync(Component component) {
        this.disconnectAsync(component, io.papermc.paper.connection.DisconnectionReason.UNKNOWN);
    }

    // Paper: abstract
    public void disconnectAsync(DisconnectionDetails disconnectionInfo) {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }

    public boolean isTransferred() {
        return this.transferred;
    }

    // Paper: abstract
    public io.papermc.paper.connection.PaperCommonConnection<?> paperConnection() {
        throw new UnsupportedOperationException("FissionMC: abstract in Paper");
    }
    // Paper end - add proper async disconnect
}
