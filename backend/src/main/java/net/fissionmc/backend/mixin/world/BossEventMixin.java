package net.fissionmc.backend.mixin.world;

import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/BossEvent.java.patch */
@Mixin(BossEvent.class)
public abstract class BossEventMixin {
    private net.kyori.adventure.bossbar.BossBar adventure; // Paper
    // TODO(behaviour): getName/setName/getProgress/setProgress/... hunks delegate to adventure when set
}
