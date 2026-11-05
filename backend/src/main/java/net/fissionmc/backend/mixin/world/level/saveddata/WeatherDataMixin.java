package net.fissionmc.backend.mixin.world.level.saveddata;

import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/saveddata/WeatherData.java.patch */
@Mixin(WeatherData.class)
public abstract class WeatherDataMixin extends SavedData {
    @Shadow private boolean raining;
    @Shadow private boolean thundering;

    // Paper start - Add cause to Weather/ThunderChangeEvents
    @org.spongepowered.asm.mixin.injection.Inject(method = "setThundering(Z)V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void paper$setThunderingUnknown(final boolean thundering, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.setThundering(thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause.UNKNOWN);
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setRaining(Z)V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void paper$setRainingUnknown(final boolean raining, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.setRaining(raining, org.bukkit.event.weather.WeatherChangeEvent.Cause.UNKNOWN);
        ci.cancel();
    }
    // Paper end - Add cause to Weather/ThunderChangeEvents

    public void setThundering(boolean thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause cause) {
        // Paper end - Add cause to Weather/ThunderChangeEvents
        // CraftBukkit start
        if (this.thundering == thundering) {
            return;
        }

        org.bukkit.World world = this.level == null ? null : this.level.getWorld();
        if (world != null) {
            org.bukkit.event.weather.ThunderChangeEvent thunder = new org.bukkit.event.weather.ThunderChangeEvent(world, thundering, cause); // Paper - Add cause to Weather/ThunderChangeEvents
            org.bukkit.Bukkit.getServer().getPluginManager().callEvent(thunder);
            if (thunder.isCancelled()) {
                return;
            }
        }
        // CraftBukkit end
        this.thundering = thundering;
        this.setDirty();
    }

    public void setRaining(boolean raining, org.bukkit.event.weather.WeatherChangeEvent.Cause cause) {
        // Paper end - Add cause to Weather/ThunderChangeEvents
        // CraftBukkit start
        if (this.raining == raining) {
            return;
        }

        org.bukkit.World world = this.level == null ? null : this.level.getWorld();
        if (world != null) {
            org.bukkit.event.weather.WeatherChangeEvent weather = new org.bukkit.event.weather.WeatherChangeEvent(world, raining, cause); // Paper - Add cause to Weather/ThunderChangeEvents
            org.bukkit.Bukkit.getServer().getPluginManager().callEvent(weather);
            if (weather.isCancelled()) {
                return;
            }
        }
        // CraftBukkit end
        this.raining = raining;
        this.setDirty();
    }

    // Paper start - pass level for events
    private net.minecraft.server.level.@org.jspecify.annotations.Nullable ServerLevel level;
    public void setLevel(final net.minecraft.server.level.ServerLevel level) {
        this.level = level;
    }
    // Paper end - pass level for events
}
