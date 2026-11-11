package net.fissionmc.craft.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;

/**
 * FissionMC: Paper's {@code Projectile.Delayed} (nested record added by Paper's Projectile.java.patch), moved here because Mixin cannot add nested classes.
 * A projectile that is created and shot but only added to the world once {@link #attemptSpawn()} runs (after PlayerLaunchProjectileEvent).
 */
// Paper start - delayed projectile spawning
public record DelayedProjectile<T extends Projectile>(T projectile, ServerLevel world, ItemStack projectileStack) {
    // Taken from net.minecraft.world.entity.projectile.Projectile.spawnProjectile(T, ServerLevel, ItemStack, Consumer<T>)
    public boolean attemptSpawn() {
        if (!this.world.addFreshEntity(this.projectile)) return false;
        this.projectile.applyOnProjectileSpawned(this.world, this.projectileStack);
        return true;
    }

    public T spawn() {
        this.attemptSpawn();
        return this.projectile();
    }

    public boolean attemptSpawn(final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        if (!this.world.addFreshEntity(this.projectile, reason)) return false;
        this.projectile.applyOnProjectileSpawned(this.world, this.projectileStack);
        return true;
    }

    public T spawn(final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        this.attemptSpawn(reason);
        return this.projectile();
    }
}
// Paper end - delayed projectile spawning
