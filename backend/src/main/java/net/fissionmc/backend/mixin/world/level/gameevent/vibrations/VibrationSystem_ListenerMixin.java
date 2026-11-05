package net.fissionmc.backend.mixin.world.level.gameevent.vibrations;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/level/gameevent/vibrations/VibrationSystem.java.patch (Listener#handleGameEvent) */
@Mixin(VibrationSystem.Listener.class)
public abstract class VibrationSystem_ListenerMixin {
    // CraftBukkit start - BlockReceiveGameEvent
    @ModifyExpressionValue(method = "handleGameEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gameevent/vibrations/VibrationSystem$User;canReceiveVibration(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)Z"))
    private boolean craftbukkit$blockReceiveGameEvent(final boolean canReceive, @Local(argsOnly = true) final ServerLevel level, @Local(argsOnly = true) final Holder<GameEvent> event,
                                                      @Local(argsOnly = true) final GameEvent.Context context, @Local(ordinal = 1) final Vec3 destination) {
        boolean defaultCancel = !canReceive;
        Entity entity = context.sourceEntity();
        org.bukkit.event.block.BlockReceiveGameEvent event1 = new org.bukkit.event.block.BlockReceiveGameEvent(org.bukkit.craftbukkit.CraftGameEvent.minecraftHolderToBukkit(event), org.bukkit.craftbukkit.block.CraftBlock.at(level, BlockPos.containing(destination)), (entity == null) ? null : entity.getBukkitEntity());
        event1.setCancelled(defaultCancel);
        level.getCraftServer().getPluginManager().callEvent(event1);
        return !event1.isCancelled();
    }
    // CraftBukkit end
}
