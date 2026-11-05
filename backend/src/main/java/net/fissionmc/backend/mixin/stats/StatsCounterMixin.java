package net.fissionmc.backend.mixin.stats;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/stats/StatsCounter.java.patch */
@Mixin(StatsCounter.class)
public abstract class StatsCounterMixin {
    // CraftBukkit start - fire Statistic events
    @Inject(method = "increment", at = @At(value = "INVOKE", target = "Lnet/minecraft/stats/StatsCounter;setValue(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/stats/Stat;I)V"), cancellable = true)
    private void craftbukkit$statisticEvents(final Player player, final Stat<?> stat, final int count, final CallbackInfo ci, @Local(ordinal = 1) final int result) {
        org.bukkit.event.Cancellable cancellable = org.bukkit.craftbukkit.event.CraftEventFactory.handleStatisticsIncrease(player, stat, ((StatsCounter) (Object) this).getValue(stat), result);
        if (cancellable != null && cancellable.isCancelled()) {
            ci.cancel();
        }
    }
    // CraftBukkit end
}
