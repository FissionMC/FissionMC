package net.fissionmc.backend.mixin.world.entity.animal.pig;

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
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.pig.Pig;

/**
 * Paper: sources/net/minecraft/world/entity/animal/pig/Pig.java.patch
 * done: PigZapEvent (the conversion returns the piglin un-added; a cancelled event leaves the pig alone), LIGHTNING spawn reason, TRANSFORMATION remove cause.
 */
@Mixin(Pig.class)
public abstract class PigMixin {
    @WrapOperation(method = "thunderHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/pig/Pig;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    private Mob paper$pigZap(final Pig self, final EntityType<?> type, final ConversionParams params, final ConversionParams.AfterConversion<?> afterConversion, final Operation<Mob> original,
                             @Local(argsOnly = true) final ServerLevel level, @Local(argsOnly = true) final LightningBolt lightningBolt) {
        // CraftBukkit start
        final Mob zombifiedPiglin = PaperContext.convert(self, type, params, afterConversion, null, null);
        if (zombifiedPiglin == null) {
            return null; // vanilla falls back to super.thunderHit
        }
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callPigZapEvent(self, lightningBolt, zombifiedPiglin).isCancelled()) {
            return zombifiedPiglin; // Paper returns here: no conversion, no super.thunderHit
        }
        level.addFreshEntity(zombifiedPiglin, CreatureSpawnEvent.SpawnReason.LIGHTNING);
        self.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.TRANSFORMATION); // CraftBukkit - add Bukkit remove cause
        // CraftBukkit end
        return zombifiedPiglin;
    }
}
