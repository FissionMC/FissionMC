package net.fissionmc.backend.mixin.world.level.border;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Shadow public abstract double getSize();
    @Shadow public abstract double getCenterX();
    @Shadow public abstract double getCenterZ();

    private @Nullable ServerLevel world; // CraftBukkit
    // TODO(behaviour): tick guard (Prevent ticking virtual world borders multiple times per server tick)
    // The three event hunks run at HEAD; when an event changed the arguments the method is re-entered once with the new values (guard) and the original call is cancelled.
    private static final ThreadLocal<Boolean> fission$reentry = ThreadLocal.withInitial(() -> false);

    // Paper start - Add worldborder events
    @Inject(method = "setCenter", at = @At("HEAD"), cancellable = true)
    private void fission$centerEvent(final double x, final double z, final CallbackInfo ci) {
        if (this.world == null || fission$reentry.get()) return;
        final io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent event = new io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent(this.world.getWorld(), this.world.getWorld().getWorldBorder(), new org.bukkit.Location(this.world.getWorld(), this.getCenterX(), 0, this.getCenterZ()), new org.bukkit.Location(this.world.getWorld(), x, 0, z));
        if (!event.callEvent()) {
            ci.cancel();
            return;
        }
        final double nx = event.getNewCenter().getX(), nz = event.getNewCenter().getZ();
        if (nx != x || nz != z) {
            fission$reentry.set(true);
            try {
                this.setCenter(nx, nz);
            } finally {
                fission$reentry.set(false);
            }
            ci.cancel();
        }
    }

    @Inject(method = "setSize", at = @At("HEAD"), cancellable = true)
    private void fission$sizeEvent(final double size, final CallbackInfo ci) {
        if (this.world == null || fission$reentry.get()) return;
        final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent event = new io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent(this.world.getWorld(), this.world.getWorld().getWorldBorder(), io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.INSTANT_MOVE, this.getSize(), size, 0);
        if (!event.callEvent()) {
            ci.cancel();
            return;
        }
        if (event.getType() == io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.STARTED_MOVE && event.getDurationTicks() > 0) { // If changed to a timed transition
            fission$reentry.set(true);
            try {
                this.lerpSizeBetween(event.getOldSize(), event.getNewSize(), event.getDurationTicks(), this.world.getGameTime());
            } finally {
                fission$reentry.set(false);
            }
            ci.cancel();
            return;
        }
        if (event.getNewSize() != size) {
            fission$reentry.set(true);
            try {
                this.setSize(event.getNewSize());
            } finally {
                fission$reentry.set(false);
            }
            ci.cancel();
        }
    }

    @Inject(method = "lerpSizeBetween", at = @At("HEAD"), cancellable = true)
    private void fission$lerpEvent(final double from, final double to, final long ticks, final long gameTime, final CallbackInfo ci) {
        if (this.world == null || fission$reentry.get()) return;
        final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type type = from == to
            ? io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.INSTANT_MOVE // Use INSTANT_MOVE because the vanilla body creates a static border if they are equal.
            : io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.STARTED_MOVE;
        final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent event = new io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent(this.world.getWorld(), this.world.getWorld().getWorldBorder(), type, from, to, ticks);
        if (!event.callEvent()) {
            ci.cancel();
            return;
        }
        if (event.getNewSize() != to || event.getDurationTicks() != ticks) {
            fission$reentry.set(true);
            try {
                this.lerpSizeBetween(from, event.getNewSize(), event.getDurationTicks(), gameTime);
            } finally {
                fission$reentry.set(false);
            }
            ci.cancel();
        }
    }
    // Paper end - Add worldborder events

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
