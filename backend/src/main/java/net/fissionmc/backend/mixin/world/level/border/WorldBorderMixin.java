package net.fissionmc.backend.mixin.world.level.border;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/border/WorldBorder.java.patch */
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {
    @Shadow public abstract void setCenter(double x, double z);
    @Shadow public abstract void setDamagePerBlock(double damagePerBlock);
    @Shadow public abstract void setSafeZone(double safeZone);
    @Shadow public abstract void setWarningBlocks(int warningBlocks);
    @Shadow public abstract void setWarningTime(int warningTime);
    @Shadow public abstract void lerpSizeBetween(double from, double to, long ticks, long gameTime);
    @Shadow public abstract void setSize(double size);

    private @Nullable ServerLevel world; // CraftBukkit
    // TODO(behaviour): WorldBorderCenterChangeEvent/BoundsChangeEvent hunks in setCenter/setSize/lerpSizeBetween, tick guard

    // Paper start - add back applySettings
    public void applySettings(WorldBorder.Settings settings) {
        this.setCenter(settings.centerX(), settings.centerZ());
        this.setDamagePerBlock(settings.damagePerBlock());
        this.setSafeZone(settings.safeZone());
        this.setWarningBlocks(settings.warningBlocks());
        this.setWarningTime(settings.warningTime());
        if (settings.lerpTime() > 0L) {
            final long gameTime = (this.world != null) ? this.world.getGameTime() : 0; // Virtual Borders don't have a World
            this.lerpSizeBetween(settings.size(), settings.lerpTarget(), settings.lerpTime(), gameTime);
        } else {
            this.setSize(settings.size());
        }
    }
    // Paper end - add back applySettings
}
