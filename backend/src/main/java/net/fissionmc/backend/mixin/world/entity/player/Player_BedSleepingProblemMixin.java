package net.fissionmc.backend.mixin.world.entity.player;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/player/Player.java.patch */
@Mixin(Player.BedSleepingProblem.class)
public abstract class Player_BedSleepingProblemMixin {
    // Surface: public static final. Mixin requires private statics; the loader widens it.
    private static final Player.BedSleepingProblem EXPLOSION = new Player.BedSleepingProblem(null); // Paper - Added to properly handle explosions in bed events
}
