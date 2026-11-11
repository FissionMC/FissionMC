package net.fissionmc.backend.mixin.world.entity.monster.hoglin;

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
import net.minecraft.world.entity.monster.hoglin.Hoglin;

/**
 * Paper: sources/net/minecraft/world/entity/monster/hoglin/Hoglin.java.patch
 * done: transform and spawn reasons of the conversion, timeInOverworld reset when cancelled.
 */
@Mixin(Hoglin.class)
public abstract class HoglinMixin {
    @Shadow public int timeInOverworld;

    @WrapOperation(method = "finishConversion", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/hoglin/Hoglin;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    private Mob paper$convertReasons(final Hoglin self, final EntityType<?> type, final ConversionParams params, final ConversionParams.AfterConversion<?> afterConversion, final Operation<Mob> original) {
        final Mob converted = PaperContext.convert(self, type, params, afterConversion, EntityTransformEvent.TransformReason.PIGLIN_ZOMBIFIED, CreatureSpawnEvent.SpawnReason.PIGLIN_ZOMBIFIED); // CraftBukkit
        // Paper start - Fix issues with mob conversion; reset to prevent event spam
        if (converted == null) {
            this.timeInOverworld = 0;
        }
        // Paper end - Fix issues with mob conversion
        return converted;
    }
}
