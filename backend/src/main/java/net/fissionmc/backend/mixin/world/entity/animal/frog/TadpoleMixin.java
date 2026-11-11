package net.fissionmc.backend.mixin.world.entity.animal.frog;

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
import net.minecraft.world.entity.animal.frog.Tadpole;

/**
 * Paper: sources/net/minecraft/world/entity/animal/frog/Tadpole.java.patch
 * done: transform and spawn reasons of the conversion, age reset when cancelled.
 */
@Mixin(Tadpole.class)
public abstract class TadpoleMixin {
    @Shadow public abstract void setAge(int age);

    @WrapOperation(method = "ageUp()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/frog/Tadpole;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    private Mob paper$convertReasons(final Tadpole self, final EntityType<?> type, final ConversionParams params, final ConversionParams.AfterConversion<?> afterConversion, final Operation<Mob> original) {
        final Mob converted = PaperContext.convert(self, type, params, afterConversion, EntityTransformEvent.TransformReason.METAMORPHOSIS, CreatureSpawnEvent.SpawnReason.METAMORPHOSIS); // CraftBukkit
        if (converted == null) {
            this.setAge(0); // Sets the age to 0 for avoid a loop if the event is canceled
        }
        return converted;
    }
}
