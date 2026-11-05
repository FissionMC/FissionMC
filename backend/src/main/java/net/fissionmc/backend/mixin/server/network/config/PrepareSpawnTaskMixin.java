package net.fissionmc.backend.mixin.server.network.config;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter.ScopedCollector;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

/**
 * Paper: sources/net/minecraft/server/network/config/PrepareSpawnTask.java.patch
 * Paper's constructor takes (server, GameProfile, listener); FissionMC keeps the vanilla constructor and
 * ServerConfigurationPacketListenerImplMixin calls {@link #fission$init} right after it.
 */
@Mixin(PrepareSpawnTask.class)
public abstract class PrepareSpawnTaskMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final @Mutable private NameAndId nameAndId;
    @Shadow @Final @Mutable private LevelLoadListener loadListener;

    // Paper start - passthrough profile and packet listener
    private com.mojang.authlib.GameProfile profile;
    private net.minecraft.server.network.ServerConfigurationPacketListenerImpl listener;
    private boolean newPlayer;

    public void fission$init(final com.mojang.authlib.GameProfile profile, final ServerConfigurationPacketListenerImpl listener) {
        this.profile = profile;
        this.listener = listener;
        // Paper end - passthrough profile and packet listener
        this.nameAndId = new net.minecraft.server.players.NameAndId(profile); // Paper - passthrough profile and packet listener - create from profile
        this.loadListener = new LevelLoadListener() { // FissionMC: LevelLoadListener.noop() does not exist in the vanilla jar
            @Override public void start(final LevelLoadListener.Stage stage, final int count) {}
            @Override public void update(final LevelLoadListener.Stage stage, final int count, final int total) {}
            @Override public void finish(final LevelLoadListener.Stage stage) {}
            @Override public void updateFocus(final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, final net.minecraft.world.level.ChunkPos pos) {}
        }; // Paper - per level load listener - this is already a no-op on dedicated server, but we moved it to Level
    }

    // FissionMC: accessors for the inner State classes' mixins
    public com.mojang.authlib.GameProfile fission$profile() { return this.profile; }
    public ServerConfigurationPacketListenerImpl fission$listener() { return this.listener; }
    public boolean fission$newPlayer() { return this.newPlayer; }
    public MinecraftServer fission$server() { return this.server; }

    // Paper - move logic in Entity to here, to use bukkit supplied world UUID & reset to main world spawn if no valid world is found
    // FissionMC: instead of replacing start(), the vanilla spawn level lookup (orElseGet, ordinal 0) is wrapped.
    @WrapOperation(method = "start", at = @At(value = "INVOKE", target = "Ljava/util/Optional;orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;", ordinal = 0))
    private Object paper$bukkitSpawnLevel(final Optional<ServerLevel> vanillaLevel, final java.util.function.Supplier<ServerLevel> fallback, final Operation<Object> original, @Local final Optional<ValueInput> loadedData) {
        this.newPlayer = loadedData.isEmpty(); // New players don't have saved data!
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> resourceKey = null; // Paper
        boolean[] invalidPlayerWorld = {false};
        bukkitData: if (loadedData.isPresent()) {
            // The main way for bukkit worlds to store the world is the world UUID despite mojang adding custom worlds
            final org.bukkit.World bWorld;
            final ValueInput playerData = loadedData.get();
            // TODO maybe convert this to a codec and use compoundTag#read, we need silent variants of that method first.
            final Optional<Long> worldUUIDMost = playerData.getLong("WorldUUIDMost");
            final Optional<Long> worldUUIDLeast = playerData.getLong("WorldUUIDLeast");
            final java.util.Optional<String> worldName = playerData.getString("world");
            if (worldUUIDMost.isPresent() && worldUUIDLeast.isPresent()) {
                bWorld = org.bukkit.Bukkit.getServer().getWorld(new java.util.UUID(worldUUIDMost.get(), worldUUIDLeast.get()));
            } else if (worldName.isPresent()) { // Paper - legacy bukkit world name
                bWorld = org.bukkit.Bukkit.getServer().getWorld(worldName.get());
            } else {
                break bukkitData; // if neither of the bukkit data points exist, proceed to the vanilla migration section
            }
            if (bWorld != null) {
                resourceKey = ((org.bukkit.craftbukkit.CraftWorld) bWorld).getHandle().dimension();
            } else {
                resourceKey = net.minecraft.world.level.Level.OVERWORLD;
                invalidPlayerWorld[0] = true;
            }
        }
        if (resourceKey == null) { // only run the vanilla logic if we haven't found a world from the bukkit data
            return original.call(vanillaLevel, fallback);
        }
        ServerLevel spawnLevel = this.server.getLevel(resourceKey);
        if (spawnLevel == null) {
            LOGGER.warn("Unknown respawn dimension {}, defaulting to overworld", resourceKey);
            spawnLevel = fallback.get();
        }
        return spawnLevel;
    }
}
