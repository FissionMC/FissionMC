package net.fissionmc.backend.mixin.stats;

import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/stats/ServerStatsCounter.java.patch */
@Mixin(ServerStatsCounter.class)
public abstract class ServerStatsCounterMixin extends StatsCounter {
    // Paper start - Moved after stat fetching for player state file
    // Moves the loading after vanilla loading, so it overrides the values.
    // Disables saving any forced stats, so it stays at the same value (without enabling disableStatSaving)
    // Fixes stat initialization to not cause a NullPointerException
    @Inject(method = "<init>", at = @At("TAIL"))
    private void paper$forcedStats(final MinecraftServer server, final Path file, final CallbackInfo ci) {
        // Spigot start
        for (Map.Entry<Identifier, Integer> entry : org.spigotmc.SpigotConfig.forcedStats.entrySet()) {
            Stat<Identifier> wrapper = Stats.CUSTOM.get(java.util.Objects.requireNonNull(BuiltInRegistries.CUSTOM_STAT.getValue(entry.getKey()))); // Paper - ensured by SpigotConfig#stats
            this.stats.put(wrapper, entry.getValue().intValue());
        }
        // Spigot end
    }
    // Paper end - Moved after stat fetching for player state file

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void spigot$disableStatSaving(final CallbackInfo ci) {
        if (org.spigotmc.SpigotConfig.disableStatSaving) ci.cancel(); // Spigot
    }

    @Inject(method = "setValue", at = @At("HEAD"), cancellable = true)
    private void paper$noForcedStatSaving(final Player player, final Stat<?> stat, final int count, final CallbackInfo ci) {
        if (org.spigotmc.SpigotConfig.disableStatSaving) { ci.cancel(); return; } // Spigot
        if (stat.getType() == Stats.CUSTOM && stat.getValue() instanceof final Identifier key && org.spigotmc.SpigotConfig.forcedStats.get(key) != null) ci.cancel(); // Paper - disable saving forced stats
    }
}
