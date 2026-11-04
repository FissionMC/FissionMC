package net.fissionmc.backend.mixin.server.players;

import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fissionmc.craft.compat.LoginResult;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/server/players/PlayerList.java.patch
 * Paper's nested PlayerList.LoginResult lives in net.fissionmc.craft.compat.LoginResult.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final public static Component CHAT_FILTERED_FULL;
    @Shadow @Final private List<ServerPlayer> players;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private ServerOpList ops;
    @Shadow @Final private UserWhiteList whitelist;
    @Shadow @Final private LayeredRegistryAccess<RegistryLayer> registries;

    @Shadow public abstract ServerPlayer respawn(ServerPlayer serverPlayer, boolean keepAllPlayerData, Entity.RemovalReason removalReason);
    @Shadow public abstract void broadcastAll(Packet<?> packet);
    @Shadow public abstract boolean isUsingWhitelist();
    @Shadow public abstract int getMaxPlayers();
    @Shadow public abstract boolean canBypassPlayerLimit(NameAndId nameAndId);
    @Shadow private boolean verifyChatTrusted(final PlayerChatMessage message) { throw new AssertionError(); }
    @Shadow private Path locateStatsFile(final GameProfile gameProfile) { throw new AssertionError(); }

    // CraftBukkit start
    private org.bukkit.craftbukkit.CraftServer cserver;

    // FissionMC: Paper runs this at the top of the constructor body; at RETURN every field CraftServer's constructor
    // reads (players) is set as well.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$createCraftServer(CallbackInfo ci) {
        this.cserver = this.server.server = new org.bukkit.craftbukkit.CraftServer((net.minecraft.server.dedicated.DedicatedServer) this.server, (PlayerList) (Object) this);
        this.server.console = new com.destroystokyo.paper.console.TerminalConsoleCommandSender(); // Paper
    }
    // CraftBukkit end

    // Surface: public. Mixin requires private fields; the loader widens it.
    private @Nullable String collideRuleTeamName; // Paper - Configurable player collision // TODO(behaviour): set up in placeNewPlayer/remove

    // Paper start - respawn event
    // TODO(behaviour): Paper's respawn body uses findRespawnPositionAndUseSpawnBlock0(..., respawnReason) and fires
    // PlayerRespawnEvent/PlayerPostRespawnEvent with the reason; here the reason is not consumed yet.
    public ServerPlayer respawn(final ServerPlayer serverPlayer, final boolean keepAllPlayerData, final Entity.RemovalReason removalReason, final org.bukkit.event.player.PlayerRespawnEvent.RespawnReason respawnReason) {
        return this.respawn(serverPlayer, keepAllPlayerData, removalReason);
    }
    // Paper end - respawn event

    // Paper start - avoid recalculating permissions if possible
    public void sendPlayerPermissionLevel(final ServerPlayer player, final boolean recalculatePermissions) {
        // Paper end - avoid recalculating permissions if possible
        LevelBasedPermissionSet permissions = this.server.getProfilePermissions(player.nameAndId());
        this.sendPlayerPermissionLevel(player, permissions, recalculatePermissions); // Paper - avoid recalculating permissions if possible
    }

    // CraftBukkit start - add a world/entity limited version
    public void broadcastAll(Packet packet, net.minecraft.world.entity.player.Player entityhuman) {
        for (ServerPlayer entityplayer : this.players) { // Paper - replace for i with for each for thread safety
            if (entityhuman != null && !entityplayer.getBukkitEntity().canSee(entityhuman.getBukkitEntity())) {
                continue;
            }
            ((ServerPlayer) entityplayer).connection.send(packet); // Paper - replace for i with for each for thread safety
        }
    }

    public void broadcastAll(Packet packet, Level world) {
        for (int i = 0; i < world.players().size(); ++i) {
            ((ServerPlayer) world.players().get(i)).connection.send(packet);
        }

    }
    // CraftBukkit end

    // Paper start - Add sendOpLevel API
    // TODO(behaviour): vanilla private sendPlayerPermissionLevel(player, permissions) should delegate here with true
    public void sendPlayerPermissionLevel(final ServerPlayer player, final LevelBasedPermissionSet permissions, final boolean recalculatePermissions) {
        // Paper end - Add sendOpLevel API
        if (player.connection != null) {
            byte eventId = switch (permissions.level()) {
                case ALL -> EntityEvent.PERMISSION_LEVEL_ALL;
                case MODERATORS -> EntityEvent.PERMISSION_LEVEL_MODERATORS;
                case GAMEMASTERS -> EntityEvent.PERMISSION_LEVEL_GAMEMASTERS;
                case ADMINS -> EntityEvent.PERMISSION_LEVEL_ADMINS;
                case OWNERS -> EntityEvent.PERMISSION_LEVEL_OWNERS;
            };
            player.connection.send(new ClientboundEntityEventPacket(player, eventId));
        }

        if (recalculatePermissions) { // Paper - Add sendOpLevel API
        player.getBukkitEntity().recalculatePermissions(); // CraftBukkit
        this.server.getCommands().sendCommands(player);
        } // Paper - Add sendOpLevel API
    }

    // Paper start - whitelist verify event / login event
    public LoginResult canBypassFullServerLogin(final NameAndId nameAndId, final LoginResult currentResult) {
        final boolean shouldKick = this.players.size() >= this.getMaxPlayers() && !this.canBypassPlayerLimit(nameAndId);
        final io.papermc.paper.event.player.PlayerServerFullCheckEvent fullCheckEvent = new io.papermc.paper.event.player.PlayerServerFullCheckEvent(
            new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId),
            io.papermc.paper.adventure.PaperAdventure.asAdventure(currentResult.message()),
            shouldKick
        );

        fullCheckEvent.callEvent();
        if (fullCheckEvent.isAllowed()) {
            return LoginResult.ALLOW;
        } else {
            return new LoginResult(
                io.papermc.paper.adventure.PaperAdventure.asVanilla(fullCheckEvent.kickMessage()), currentResult.result()
            );
        }
    }

    // TODO(behaviour): Paper's canPlayerLogin returns LoginResult and uses isWhiteListedLogin/canBypassFullServerLogin
    public LoginResult isWhiteListedLogin(NameAndId nameAndId) {
        boolean isOp = ((StoredUserListMixin) (Object) this.ops).fission$contains(nameAndId); // FissionMC: protected, same-package access in Paper
        boolean isWhitelisted = !this.isUsingWhitelist() || isOp || ((StoredUserListMixin) (Object) this.whitelist).fission$contains(nameAndId); // FissionMC: protected, same-package access in Paper

        final net.kyori.adventure.text.Component configuredMessage = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(org.spigotmc.SpigotConfig.whitelistMessage);
        final com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent event
            = new com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent(new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId), this.isUsingWhitelist(), isWhitelisted, isOp, configuredMessage);
        event.callEvent();
        if (!event.isWhitelisted()) {
            return new LoginResult(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.kickMessage() == null ? configuredMessage : event.kickMessage()), org.bukkit.event.player.PlayerLoginEvent.Result.KICK_WHITELIST);
        }

        return LoginResult.ALLOW;
    }
    // Paper end

    // Paper start
    // TODO(behaviour): vanilla broadcastChatMessage(message, sender, chatType) / the private 4-arg overload should delegate
    // to these with unsignedFunction = null
    public void broadcastChatMessage(final PlayerChatMessage message, final ServerPlayer sender, final ChatType.Bound chatType, final @Nullable Function<net.kyori.adventure.audience.Audience, Component> unsignedFunction) {
        // Paper end
        this.broadcastChatMessage(message, sender::shouldFilterMessageTo, sender, chatType, unsignedFunction); // Paper
    }

    public void broadcastChatMessage(final PlayerChatMessage message, final Predicate<ServerPlayer> isFiltered, final @Nullable ServerPlayer senderPlayer, final ChatType.Bound chatType, final @Nullable Function<net.kyori.adventure.audience.Audience, Component> unsignedFunction) {
        // Paper end
        boolean trusted = this.verifyChatTrusted(message);
        this.server.logChatMessage((unsignedFunction == null ? message.decoratedContent() : unsignedFunction.apply(this.server.console)), chatType, trusted ? null : "Not Secure"); // Paper
        OutgoingChatMessage tracked = OutgoingChatMessage.create(message);
        boolean wasFullyFiltered = false;

        Packet<?> disguised = senderPlayer != null && unsignedFunction == null ? new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(tracked.content(), chatType) : null; // Paper - don't send player chat packets from vanished players
        for (ServerPlayer player : this.players) {
            boolean filtered = isFiltered.test(player);
            // Paper start - don't send player chat packets from vanished players
            if (senderPlayer != null && !player.getBukkitEntity().canSee(senderPlayer.getBukkitEntity())) {
                player.connection.send(unsignedFunction != null
                    ? new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(unsignedFunction.apply(player.getBukkitEntity()), chatType)
                    : disguised);
                continue;
            }
            player.sendChatMessage(tracked, filtered, chatType, unsignedFunction == null ? null : unsignedFunction.apply(player.getBukkitEntity()));
            // Paper end
            wasFullyFiltered |= filtered && message.isFullyFiltered();
        }

        if (wasFullyFiltered && senderPlayer != null) {
            senderPlayer.sendSystemMessage(CHAT_FILTERED_FULL);
        }
    }

    // CraftBukkit start
    // TODO(behaviour): Paper replaces getPlayerStats(Player) (per-UUID map) with these; ServerPlayer's constructor calls the vanilla one
    public ServerStatsCounter getPlayerStats(final ServerPlayer player) {
        GameProfile gameProfile = player.getGameProfile();
        ServerStatsCounter playerStatsCounter = player.getStats();
        if (playerStatsCounter == null) {
            return this.getPlayerStats(gameProfile);
        } else {
            return playerStatsCounter;
        }
    }

    public ServerStatsCounter getPlayerStats(final GameProfile gameProfile) {
            Path targetFile = this.locateStatsFile(gameProfile);
            return new ServerStatsCounter(this.server, targetFile);
    }
    // CraftBukkit end

    // Paper start - API for updating recipes on clients
    // TODO(behaviour): vanilla reloadResources() should call these three
    public void reloadAdvancementData() {
        // Paper end - API for updating recipes on clients
        // CraftBukkit start
        for (ServerPlayer player : this.players) {
            player.getAdvancements().reload(this.server.getAdvancements());
            player.getAdvancements().flushDirty(player, false); // CraftBukkit - trigger immediate flush of advancements
        }
        // CraftBukkit end
        // Paper start - API for updating recipes on clients
    }

    public void reloadTagData() {
        this.broadcastAll(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(this.registries)));
        // Paper end - API for updating recipes on clients
    }

    public void reloadRecipes() {
        // CraftBukkit end
        RecipeManager recipeManager = this.server.getRecipeManager();
        ClientboundUpdateRecipesPacket recipes = new ClientboundUpdateRecipesPacket(
            recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes()
        );

        for (ServerPlayer player : this.players) {
            player.connection.send(recipes);
            player.getRecipeBook().sendInitialRecipeBook(player);
        }
    }
}
