package net.fissionmc.backend.mixin.server.commands;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Set;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/server/commands/TeleportCommand.java.patch (teleport cause COMMAND) */
@Mixin(TeleportCommand.class)
public abstract class TeleportCommandMixin {
    @WrapOperation(method = "performTeleport", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z"))
    private static boolean paper$teleportCause(final Entity victim, final ServerLevel level, final double x, final double y, final double z, final Set<Relative> relatives, final float yRot, final float xRot, final boolean setCamera, final Operation<Boolean> original) {
        return victim.teleportTo(level, x, y, z, relatives, yRot, xRot, setCamera, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND); // Paper - teleport cause
    }
}
