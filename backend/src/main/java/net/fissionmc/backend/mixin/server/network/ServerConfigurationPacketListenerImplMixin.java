package net.fissionmc.backend.mixin.server.network;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin extends ServerCommonPacketListenerImpl {
    protected ServerConfigurationPacketListenerImplMixin(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    // Surface: public. Mixin requires private fields; the loader widens it.
    private io.papermc.paper.connection.PaperPlayerConfigurationConnection paperConnection; // Paper

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$init(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie, final CallbackInfo ci) {
        this.paperConnection = new io.papermc.paper.connection.PaperPlayerConfigurationConnection((ServerConfigurationPacketListenerImpl) (Object) this); // Paper
    }

    @Shadow @Final private com.mojang.authlib.GameProfile gameProfile;

    // Paper - pass full GameProfile & listener for events (Paper changes the PrepareSpawnTask constructor; here the extra args are set right after construction)
    @WrapOperation(method = "returnToWorld", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/server/network/config/PrepareSpawnTask;"))
    private PrepareSpawnTask paper$passProfileAndListener(final MinecraftServer server, final NameAndId nameAndId, final Operation<PrepareSpawnTask> original) {
        final PrepareSpawnTask task = original.call(server, nameAndId);
        task.fission$init(this.gameProfile, (ServerConfigurationPacketListenerImpl) (Object) this);
        return task;
    }

    @Shadow @Final private java.util.Queue<net.minecraft.server.network.ConfigurationTask> configurationTasks;
    @Shadow private void finishCurrentTask(final net.minecraft.server.network.ConfigurationTask.Type type) { throw new AssertionError(); }

    @Inject(method = "startConfiguration", at = @At("HEAD"))
    private void paper$initialConfigureEvent(final CallbackInfo ci) {
        new io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent(this.paperConnection).callEvent(); // Paper
    }

    // Paper - PlayerLinksSendEvent
    @ModifyExpressionValue(method = "startConfiguration", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;serverLinks()Lnet/minecraft/server/ServerLinks;"))
    private net.minecraft.server.ServerLinks paper$linksEvent(final net.minecraft.server.ServerLinks serverLinks) {
        org.bukkit.craftbukkit.CraftServerLinks links = new org.bukkit.craftbukkit.CraftServerLinks(serverLinks);
        new org.bukkit.event.player.PlayerLinksSendEvent(this.paperConnection, links).callEvent();
        return links.getServerLinks();
    }

    @Inject(method = "startConfiguration", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;addOptionalTasks()V", shift = At.Shift.AFTER))
    private void paper$configurationTask(final CallbackInfo ci) {
        this.configurationTasks.add(new io.papermc.paper.connection.PaperConfigurationTask((ServerConfigurationPacketListenerImpl) (Object) this)); // Paper
    }

    // Paper - Code of Conduct event. TODO(behaviour): Paper also fires it when no code of conduct is configured but listeners exist.
    @ModifyReturnValue(method = "lambda$addOptionalTasks$0", at = @At("RETURN"))
    private String paper$codeOfConductEvent(final String codeOfConduct) {
        io.papermc.paper.event.connection.configuration.PlayerCodeOfConductSendEvent event = new io.papermc.paper.event.connection.configuration.PlayerCodeOfConductSendEvent(this.paperConnection, codeOfConduct);
        event.callEvent();
        String result = event.getCodeOfConduct();
        if (result == null) {
            // Immediately bail out of this task. On MC updates, double check it's still okay to finish task within startNextTask.
            this.finishCurrentTask(net.minecraft.server.network.config.ServerCodeOfConductConfigurationTask.TYPE);
        }
        return result;
    }

    @Inject(method = "handleClientInformation", at = @At("HEAD"))
    private void paper$configLocale(final net.minecraft.network.protocol.common.ServerboundClientInformationPacket packet, final CallbackInfo ci) {
        this.connection.channel.attr(io.papermc.paper.adventure.PaperAdventure.LOCALE_ATTRIBUTE).set(net.kyori.adventure.translation.Translator.parseLocale(packet.information().language())); // Paper
    }

    @Inject(method = "handleResourcePackResponse", at = @At("HEAD"))
    private void paper$configPackStatus(final net.minecraft.network.protocol.common.ServerboundResourcePackPacket packet, final CallbackInfo ci) {
        this.connection.resourcePackStatus = org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.values()[packet.action().ordinal()]; // Paper
    }

    // Paper - Ignore resource pack requests that are not vanilla
    @ModifyExpressionValue(method = "handleResourcePackResponse", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/ServerboundResourcePackPacket$Action;isTerminal()Z"))
    private boolean paper$onlyVanillaPack(final boolean terminal, @Local(argsOnly = true) final net.minecraft.network.protocol.common.ServerboundResourcePackPacket packet) {
        return terminal && packet.id().equals(this.server.getServerResourcePack().map(MinecraftServer.ServerResourcePackInfo::id).orElse(null));
    }

    // Paper - Login event logic
    @WrapOperation(method = "handleConfigurationFinished", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;canPlayerLogin(Ljava/net/SocketAddress;Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/network/chat/Component;"))
    private net.minecraft.network.chat.Component paper$loginResult(final net.minecraft.server.players.PlayerList playerList, final java.net.SocketAddress address, final NameAndId nameAndId, final Operation<net.minecraft.network.chat.Component> original) {
        return org.bukkit.craftbukkit.event.CraftEventFactory.handleLoginResult(playerList.fission$canPlayerLogin(address, nameAndId), this.paperConnection, this.connection, this.gameProfile, this.server, false);
    }

    // Paper start - configuration phase API
    @Override
    public io.papermc.paper.connection.PlayerCommonConnection getApiConnection() {
        return this.paperConnection;
    }

    @Override
    public net.kyori.adventure.audience.Audience getAudience() {
        return this.paperConnection.getAudience();
    }
    // Paper end - configuration phase API

    // Paper start
    @Override
    public void disconnectAsync(final DisconnectionDetails disconnectionInfo) {
        if (this.cserver.isPrimaryThread()) {
            this.disconnect(disconnectionInfo);
            return;
        }

        this.connection.setReadOnly();
        this.server.scheduleOnMain(() -> {
            this.disconnect(disconnectionInfo); // Currently you cannot cancel disconnect during the config stage
        });
    }

    @Override
    public io.papermc.paper.connection.PaperPlayerConfigurationConnection paperConnection() {
        return paperConnection;
    }
    // Paper end
}
