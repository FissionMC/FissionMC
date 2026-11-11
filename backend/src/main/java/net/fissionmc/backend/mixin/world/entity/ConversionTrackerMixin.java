package net.fissionmc.backend.mixin.world.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperContext;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.world.entity.ConversionTracker;

/**
 * Paper: sources/net/minecraft/world/entity/ConversionTracker.java.patch
 * done: spawn and transform reasons, conversion time reset when the conversion is cancelled.
 */
@Mixin(ConversionTracker.class)
public abstract class ConversionTrackerMixin<M extends Mob> {
    @Shadow public int conversionTime;
    @Shadow @org.spongepowered.asm.mixin.Final private int totalConversionTime;

    // Paper start - add spawn and transform reasons
    private EntityTransformEvent.TransformReason transformReason = EntityTransformEvent.TransformReason.UNKNOWN;
    private CreatureSpawnEvent.SpawnReason spawnReason = CreatureSpawnEvent.SpawnReason.DEFAULT;

    @SuppressWarnings("unchecked")
    public ConversionTracker<M> setEventReasons(final CreatureSpawnEvent.SpawnReason spawnReason, final EntityTransformEvent.TransformReason transformReason) {
        this.spawnReason = spawnReason;
        this.transformReason = transformReason;
        return (ConversionTracker<M>) (Object) this;
    }

    @WrapOperation(method = "doConversion", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    private Mob paper$reasons(final Mob self, final EntityType<?> type, final ConversionParams params, final ConversionParams.AfterConversion<?> afterConversion, final Operation<Mob> original) {
        final Mob mob = PaperContext.convert(self, type, params, afterConversion, this.transformReason, this.spawnReason);
        if (mob == null) {
            // Reset conversion time to prevent event spam
            this.conversionTime = this.totalConversionTime;
        }
        return mob;
    }
    // Paper end - add spawn and transform reasons
}
