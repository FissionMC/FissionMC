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
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BeaconBlockEntity.java.patch
 * done: BeaconEffectEvent + custom range in applyEffects (wrapped at the tick call site), BeaconActivated/DeactivatedEvent (tick: compared with the level count at HEAD),
 * deactivate event + MC-153086 in setRemoved, "Paper.Range" and "Levels" load/save, unfiltered effect load (SPIGOT-3598).
 * left: BlockLockCheckEvent in createMenu.
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

    @Shadow private List<?> beamSections;

    // Paper start - beacon activation/deactivation events: tick does not change `levels` before its own previousLevels read, so HEAD holds the previous value
    private static final ThreadLocal<int[]> paper$previousLevels = ThreadLocal.withInitial(() -> new int[1]);

    @Inject(method = "tick", at = @At("HEAD"))
    private static void paper$rememberLevels(final Level level, final BlockPos pos, final net.minecraft.world.level.block.state.BlockState state, final BeaconBlockEntity entity, final CallbackInfo ci) {
        paper$previousLevels.get()[0] = ((BeaconBlockEntityMixin) (Object) entity).levels;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private static void paper$activationEvents(final Level level, final BlockPos pos, final net.minecraft.world.level.block.state.BlockState state, final BeaconBlockEntity entity, final CallbackInfo ci) {
        final int previousLevels = paper$previousLevels.get()[0];
        final int now = ((BeaconBlockEntityMixin) (Object) entity).levels;
        if (previousLevels <= 0 && now > 0) {
            new io.papermc.paper.event.block.BeaconActivatedEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent();
        } else if (previousLevels > 0 && now <= 0) {
            new io.papermc.paper.event.block.BeaconDeactivatedEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent();
        }
    }
    // Paper end - beacon activation/deactivation events

    // Paper start - Custom beacon ranges + BeaconEffectEvent
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;applyEffects(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;ILnet/minecraft/core/Holder;Lnet/minecraft/core/Holder;)V"))
    private static void paper$applyEffects(final Level level, final BlockPos worldPosition, final int levels, final @Nullable Holder<MobEffect> primaryPower, final @Nullable Holder<MobEffect> secondaryPower, final Operation<Void> original, @Local(argsOnly = true) final BeaconBlockEntity entity) {
        if (level.isClientSide() || primaryPower == null) return;
        final int baseAmp = computeEffectAmplifier(levels, primaryPower, secondaryPower);
        final int durationTicks = computeEffectDuration(levels);
        final List<Player> players = getHumansInRange(level, worldPosition, levels, entity);
        paper$applyAndCallEvent(level, worldPosition, players, new MobEffectInstance(primaryPower, durationTicks, baseAmp, true, true), true);
        if (hasSecondaryEffect(levels, primaryPower, secondaryPower)) {
            paper$applyAndCallEvent(level, worldPosition, players, new MobEffectInstance(secondaryPower, durationTicks, 0, true, true), false);
        }
    }

    private static void paper$applyAndCallEvent(final Level level, final BlockPos position, final List<Player> players, final MobEffectInstance mobEffectInstance, final boolean isPrimary) {
        final org.bukkit.potion.PotionEffect apiEffect = org.bukkit.craftbukkit.potion.CraftPotionUtil.toBukkit(mobEffectInstance);
        final org.bukkit.craftbukkit.block.CraftBlock apiBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, position);
        for (final Player player : players) {
            final com.destroystokyo.paper.event.block.BeaconEffectEvent event = new com.destroystokyo.paper.event.block.BeaconEffectEvent(apiBlock, apiEffect, (org.bukkit.entity.Player) player.getBukkitEntity(), isPrimary);
            if (!event.callEvent()) continue;
            player.addEffect(org.bukkit.craftbukkit.potion.CraftPotionUtil.fromBukkit(event.getEffect()), org.bukkit.event.entity.EntityPotionEffectEvent.Cause.BEACON);
        }
    }
    // Paper end - Custom beacon ranges + BeaconEffectEvent

    // Paper start - beacon deactivation event + fix MC-153086
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void paper$removedEvent(final CallbackInfo ci) {
        final BeaconBlockEntity self = (BeaconBlockEntity) (Object) this;
        if (self.getLevel() != null) {
            new io.papermc.paper.event.block.BeaconDeactivatedEvent(org.bukkit.craftbukkit.block.CraftBlock.at(self.getLevel(), self.getBlockPos())).callEvent();
        }
    }

    @WrapOperation(method = "setRemoved", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;playSound(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;)V"))
    private void paper$deactivateSoundOnlyWhenActive(final Level level, final BlockPos pos, final net.minecraft.sounds.SoundEvent sound, final Operation<Void> original) {
        if (this.levels > 0 && !this.beamSections.isEmpty()) original.call(level, pos, sound);
    }
    // Paper end

    // Paper start - persistence: custom range, levels, non-default effects (SPIGOT-3598, SPIGOT-5053)
    @Inject(method = "loadEffect", at = @At("HEAD"), cancellable = true)
    private static void paper$unfilteredEffect(final ValueInput input, final String field, final CallbackInfoReturnable<Holder<MobEffect>> cir) {
        cir.setReturnValue(input.read(field, net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.holderByNameCodec()).orElse(null));
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void paper$loadExtra(final ValueInput input, final CallbackInfo ci) {
        this.levels = input.getIntOr("Levels", 0);
        this.effectRange = input.getDoubleOr(PAPER_RANGE_TAG, -1);
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void paper$saveExtra(final ValueOutput output, final CallbackInfo ci) {
        output.putDouble(PAPER_RANGE_TAG, this.effectRange);
    }
    // Paper end
}
