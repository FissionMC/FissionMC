package net.fissionmc.backend.mixin.world.damagesource;

import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link DamageSource} (paper-server/patches/sources/net/minecraft/world/damagesource/DamageSource.java.patch).
 */
@Mixin(DamageSource.class)
public abstract class DamageSourceMixin {
    @Shadow @Final private Holder<DamageType> type;
    @Shadow @Final private @Nullable Entity causingEntity;
    @Shadow @Final private @Nullable Entity directEntity;
    @Shadow @Final private @Nullable Vec3 damageSourcePosition;

    // CraftBukkit start
    private org.bukkit.event.entity.EntityDamageEvent.@Nullable DamageCause knownCause; // When the damage event cause is known by the context of the call rather than the damage source data
    @Nullable
    private Entity eventEntityDamager = null; // Relevant entity set when the game doesn't normally set a causingEntity/directEntity
    private org.bukkit.block.@Nullable Block eventBlockDamager; // Relevant block set. damageSourcePosition is only used for bad respawn point explosion or custom damage
    private org.bukkit.block.@Nullable BlockState fromBlockSnapshot; // Captured block snapshot when the eventBlockDamager is not relevant (e.g. for bad respawn point explosions the block is already removed)
    private boolean critical; // Supports arrows and sweeping damage
    private net.kyori.adventure.pointer.Pointers damageContext = net.kyori.adventure.pointer.Pointers.empty(); // FissionMC: Paper's constructor parameter; the vanilla constructors default it to empty like Paper's delegating constructor

    public DamageSource knownCause(final org.bukkit.event.entity.EntityDamageEvent.DamageCause cause) {
        final DamageSource damageSource = this.copy();
        ((DamageSourceMixin) (Object) damageSource).knownCause = cause;
        return damageSource;
    }

    public org.bukkit.event.entity.EntityDamageEvent.@Nullable DamageCause knownCause() {
        return this.knownCause;
    }

    @Nullable
    public Entity eventEntityDamager() {
        return this.eventEntityDamager;
    }

    public DamageSource eventEntityDamager(final Entity entity) {
        if (this.directEntity == entity) { // no changes needed but useful for data driven damage
            return (DamageSource) (Object) this;
        }

        if (this.directEntity != null) {
            throw new IllegalStateException("Cannot set an event damager when another direct entity is already set (report a bug to Paper)");
        }
        final DamageSource damageSource = this.copy();
        ((DamageSourceMixin) (Object) damageSource).eventEntityDamager = entity;
        return damageSource;
    }

    public org.bukkit.block.@Nullable Block eventBlockDamager() {
        return this.eventBlockDamager;
    }

    public DamageSource eventBlockDamager(final net.minecraft.world.level.LevelAccessor level, final net.minecraft.core.@Nullable BlockPos pos) {
        if (pos == null) {
            return (DamageSource) (Object) this;
        }

        final DamageSource damageSource = this.copy();
        ((DamageSourceMixin) (Object) damageSource).eventBlockDamager = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
        return damageSource;
    }

    public org.bukkit.block.@Nullable BlockState causingBlockSnapshot() {
        return this.fromBlockSnapshot;
    }

    public DamageSource causingBlockSnapshot(final org.bukkit.block.BlockState blockState) {
        if (this.eventBlockDamager != null) {
            throw new IllegalStateException("Cannot set a block snapshot when an event block damager is already set (report a bug to Paper)");
        }
        final DamageSource damageSource = this.copy();
        ((DamageSourceMixin) (Object) damageSource).fromBlockSnapshot = blockState;
        return damageSource;
    }

    public boolean isCritical() {
        return this.critical;
    }

    public DamageSource critical() {
        final DamageSource damageSource = this.copy();
        ((DamageSourceMixin) (Object) damageSource).critical = true;
        return damageSource;
    }

    public net.kyori.adventure.pointer.Pointers getDamageContext() {
        return this.damageContext;
    }

    // Cloning the instance lets us return unique instances of DamageSource without affecting constants defined in DamageSources
    private DamageSource copy() {
        final DamageSource damageSource = create(this.type, this.directEntity, this.causingEntity, this.damageSourcePosition, this.damageContext);
        final DamageSourceMixin copy = (DamageSourceMixin) (Object) damageSource;
        copy.knownCause = this.knownCause;
        copy.eventEntityDamager = this.eventEntityDamager;
        copy.eventBlockDamager = this.eventBlockDamager;
        copy.fromBlockSnapshot = this.fromBlockSnapshot;
        copy.critical = this.critical;
        return damageSource;
    }
    // CraftBukkit end

    // FissionMC: static factory replacing Paper's constructor DamageSource(Holder, Entity, Entity, Vec3, Pointers) // Paper - Add damage context
    private static DamageSource create(
        final Holder<DamageType> type, final @Nullable Entity directEntity, final @Nullable Entity causingEntity, final @Nullable Vec3 damageSourcePosition, final net.kyori.adventure.pointer.Pointers damageContext
    ) {
        final DamageSource damageSource = new DamageSource(type, directEntity, causingEntity, damageSourcePosition);
        ((DamageSourceMixin) (Object) damageSource).damageContext = damageContext;
        return damageSource;
    }
}
