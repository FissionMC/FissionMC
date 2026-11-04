package net.fissionmc.backend.mixin.world.level.block.entity;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BeaconBlockEntity.java.patch
 * TODO(behaviour): applyEffects uses getHumansInRange/BeaconEffectEvent, effect range load/save ("Paper.Range"),
 * BeaconActivated/DeactivatedEvent, MC-153086 fix.
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityMixin {
    @Shadow private int levels;
    @Shadow private @Nullable Holder<MobEffect> primaryPower;
    @Shadow private @Nullable Holder<MobEffect> secondaryPower;

    // CraftBukkit start - add fields and methods
    public org.bukkit.potion.@Nullable PotionEffect getPrimaryEffect() {
        return (this.primaryPower != null)
            ? org.bukkit.craftbukkit.potion.CraftPotionUtil.toBukkit(new MobEffectInstance(
                this.primaryPower,
                computeEffectDuration(this.levels),
                computeEffectAmplifier(this.levels, this.primaryPower, this.secondaryPower),
                true,
                true
            ))
            : null;
    }

    public org.bukkit.potion.@Nullable PotionEffect getSecondaryEffect() {
        return (hasSecondaryEffect(this.levels, this.primaryPower, this.secondaryPower))
            ? org.bukkit.craftbukkit.potion.CraftPotionUtil.toBukkit(new MobEffectInstance(
                this.secondaryPower,
                computeEffectDuration(this.levels),
                computeEffectAmplifier(this.levels, this.primaryPower, this.secondaryPower),
                true,
                true
            ))
            : null;
    }
    // CraftBukkit end
    // Paper start - Custom beacon ranges
    private final String PAPER_RANGE_TAG = "Paper.Range";
    private double effectRange = -1;

    public double getEffectRange() {
        if (this.effectRange < 0) {
            return this.levels * 10 + 10;
        } else {
            return effectRange;
        }
    }

    public void setEffectRange(double range) {
        this.effectRange = range;
    }

    public void resetEffectRange() {
        this.effectRange = -1;
    }
    // Paper end - Custom beacon ranges

    // Paper start - diff out applyEffects logic components
    // Generally smarter than spigot trying to split the logic up, as that diff is giant.
    private static int computeEffectDuration(final int beaconLevel) {
        return (9 + beaconLevel * 2) * 20; // Diff from applyEffects
    }

    private static int computeEffectAmplifier(final int beaconLevel, @Nullable Holder<MobEffect> primaryEffect, @Nullable Holder<MobEffect> secondaryEffect) {
        int baseAmp = 0;
        if (beaconLevel >= 4 && Objects.equals(primaryEffect, secondaryEffect)) {
            baseAmp = 1;
        }
        return baseAmp;
    }

    private static double computeBeaconRange(final int beaconLevel) {
        return beaconLevel * 10 + 10; // Diff from applyEffects
    }

    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static List<Player> getHumansInRange(final Level level, final BlockPos pos, final int beaconLevel, final @Nullable BeaconBlockEntity blockEntity) {
        final double range = blockEntity != null ? blockEntity.getEffectRange() : computeBeaconRange(beaconLevel);
        AABB aabb = new AABB(pos).inflate(range).expandTowards(0.0, level.getHeight(), 0.0); // Diff from applyEffects
        // Improve performance of human lookup by switching to a global player iteration when searching over 128 blocks
        List<Player> list;
        if (range <= 128.0) {
            list = level.getEntitiesOfClass(Player.class, aabb); // Diff from applyEffect
        } else {
            list = new java.util.ArrayList<>();
            for (final Player player : level.players()) {
                if (!net.minecraft.world.entity.EntitySelector.NO_SPECTATORS.test(player)) continue;
                if (player.getBoundingBox().intersects(aabb)) {
                    list.add(player);
                }
            }
        }
        return list;
    }

    private static boolean hasSecondaryEffect(final int beaconLevel, final Holder<MobEffect> primaryEffect, final @Nullable Holder<MobEffect> secondaryEffect) {
        return beaconLevel >= 4 && !Objects.equals(primaryEffect, secondaryEffect) && secondaryEffect != null;
    }
    // Paper end - diff out applyEffects logic components
}
