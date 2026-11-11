package net.fissionmc.backend.mixin.world.entity.animal.cow;

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
import net.minecraft.world.entity.animal.cow.MushroomCow;

/**
 * Paper: sources/net/minecraft/world/entity/animal/cow/MushroomCow.java.patch
 * done: transform and spawn reasons of the conversion (custom shear drops not ported).
 */
@Mixin(MushroomCow.class)
public abstract class MushroomCowMixin {

    @WrapOperation(method = "shear", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/cow/MushroomCow;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    private Mob paper$convertReasons(final MushroomCow self, final EntityType<?> type, final ConversionParams params, final ConversionParams.AfterConversion<?> afterConversion, final Operation<Mob> original) {
        final Mob converted = PaperContext.convert(self, type, params, afterConversion, EntityTransformEvent.TransformReason.SHEARED, CreatureSpawnEvent.SpawnReason.SHEARED); // CraftBukkit
        return converted;
    }
}
