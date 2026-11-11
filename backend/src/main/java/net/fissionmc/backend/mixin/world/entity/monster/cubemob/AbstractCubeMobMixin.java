package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/monster/cubemob/AbstractCubeMob.java.patch */
@Mixin(AbstractCubeMob.class)
public abstract class AbstractCubeMobMixin {
    private boolean canWander = true; // Paper - Slime pathfinder events
    // done: "Paper.canWander" save/load, SlimeSplitEvent + EntityTransformEvent(SPLIT) + SLIME_SPLIT spawn reason; the goals (AbstractCubeMob_*GoalMixin) fire the slime pathfinder events and respect canWander.

    @org.spongepowered.asm.mixin.injection.Inject(method = "addAdditionalSaveData", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void paper$saveWander(final net.minecraft.world.level.storage.ValueOutput output, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        output.putBoolean("Paper.canWander", this.canWander); // Paper
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "readAdditionalSaveData", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void paper$loadWander(final net.minecraft.world.level.storage.ValueInput input, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.canWander = input.getBooleanOr("Paper.canWander", true); // Paper
    }

    // CraftBukkit start - SlimeSplitEvent: a cancelled event (or count 0) splits into nothing
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "remove", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/cubemob/AbstractCubeMob;getSplitCount()I"))
    private int paper$splitEvent(final int count) {
        org.bukkit.event.entity.SlimeSplitEvent event = new org.bukkit.event.entity.SlimeSplitEvent((org.bukkit.entity.AbstractCubeMob) ((AbstractCubeMob) (Object) this).getBukkitEntity(), count);
        if (event.callEvent() && event.getCount() > 0) {
            return event.getCount();
        }
        return 0;
    }

    @org.spongepowered.asm.mixin.Unique private java.util.List<net.minecraft.world.entity.LivingEntity> paper$splitMobs;

    // the split cubes are collected un-added (null reasons), one EntityTransformEvent decides about all of them
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "remove", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/cubemob/AbstractCubeMob;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private net.minecraft.world.entity.Mob paper$collectSplit(final AbstractCubeMob self, final net.minecraft.world.entity.EntityType type, final net.minecraft.world.entity.ConversionParams params, final net.minecraft.world.entity.EntitySpawnReason spawnReason, final net.minecraft.world.entity.ConversionParams.AfterConversion afterConversion, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.entity.Mob> original) {
        final net.minecraft.world.entity.Mob converted = self.convertTo(type, params, spawnReason, afterConversion, null, null);
        if (converted != null) {
            if (this.paper$splitMobs == null) this.paper$splitMobs = new java.util.ArrayList<>();
            this.paper$splitMobs.add(converted);
        }
        return converted;
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "remove", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/AgeableMob;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void paper$spawnSplit(final net.minecraft.world.entity.Entity.RemovalReason reason, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        final java.util.List<net.minecraft.world.entity.LivingEntity> cubeMobs = this.paper$splitMobs;
        this.paper$splitMobs = null;
        final AbstractCubeMob self = (AbstractCubeMob) (Object) this;
        if (cubeMobs == null || cubeMobs.isEmpty() || org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTransformEvent(self, cubeMobs, org.bukkit.event.entity.EntityTransformEvent.TransformReason.SPLIT).isCancelled()) {
            return;
        }
        for (net.minecraft.world.entity.LivingEntity cubeMob : cubeMobs) {
            self.level().addFreshEntity(cubeMob, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SLIME_SPLIT);
        }
    }
    // CraftBukkit end

    // Paper start - Slime pathfinder events
    public boolean canWander() {
        return this.canWander;
    }

    public void setWander(boolean canWander) {
        this.canWander = canWander;
    }
    // Paper end - Slime pathfinder events
}
