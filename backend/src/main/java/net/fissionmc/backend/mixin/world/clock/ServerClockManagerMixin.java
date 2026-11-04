package net.fissionmc.backend.mixin.world.clock;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Paper: sources/net/minecraft/world/clock/ServerClockManager.java.patch
 * TODO(behaviour): per-world time (level field, init(server, level), broadcastUpdates per player, addTicks(long), TimerMove).
 */
@Mixin(ServerClockManager.class)
public abstract class ServerClockManagerMixin {
    @Shadow private MinecraftServer server;
    @Shadow @Final private Map<Holder<WorldClock>, ServerClockManager.ServerClockInstance> clocks;

    @Shadow private long getGameTime() { throw new AssertionError(); }

    // Paper start - per-player time
    public ClientboundSetTimePacket createFullSyncPacket(final ServerPlayer player) {
        final Map<Holder<WorldClock>, ClockNetworkState> updates = new HashMap<>(this.clocks.size());
        this.clocks.forEach((clock, instance) -> updates.put(clock, this.fission$packNetworkState(clock, instance, player)));
        return new ClientboundSetTimePacket(this.getGameTime(), updates);
    }

    @Unique
    private ClockNetworkState fission$packNetworkState(
        final Holder<WorldClock> clock,
        final ServerClockManager.ServerClockInstance instance,
        final ServerPlayer player
    ) {
        if (player != null && player.level().dimensionType().defaultClock().filter(clock::equals).isPresent()) {
            final boolean paused = !player.relativeTime || instance.isPaused() || !this.fission$advanceTime();
            return new ClockNetworkState(player.getPlayerTime(), instance.partialTick(), paused ? 0.0F : instance.rate());
        }
        return instance.packNetworkState(this.server);
    }

    @Unique
    private boolean fission$advanceTime() {
        return this.server.getGlobalGameRules().get(GameRules.ADVANCE_TIME); // TODO(behaviour): per-world game rules (Paper uses level's rules when bound)
    }
    // Paper end - per-player time
}
