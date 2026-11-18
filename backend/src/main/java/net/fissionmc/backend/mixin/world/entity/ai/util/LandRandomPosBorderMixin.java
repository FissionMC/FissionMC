package net.fissionmc.backend.mixin.world.entity.ai.util;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.ai.util.RandomPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FissionMC static regions, mob AI border: LandRandomPos.getPos(mob, h, v, weight) (the plain land wander of WaterAvoidingRandomStrollGoal, village strolls, panic behaviours) calls the
 * mob-less RandomPos.generateRandomPos(Supplier, ToDoubleFunction), which RandomPosBorderMixin does not see. Filter its candidate supplier here so the 10 attempts retry. New file.
 */
@Mixin(LandRandomPos.class)
public abstract class LandRandomPosBorderMixin {
    @WrapOperation(method = "getPos(Lnet/minecraft/world/entity/PathfinderMob;IILjava/util/function/ToDoubleFunction;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/util/RandomPos;generateRandomPos(Ljava/util/function/Supplier;Ljava/util/function/ToDoubleFunction;)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 fission$noLandPositionsAcrossTheBorder(final Supplier<BlockPos> positions, final ToDoubleFunction<BlockPos> weight, final Operation<Vec3> original, @Local(argsOnly = true) final PathfinderMob mob) {
        return original.call(RegionsBackend.mobAiBorderActive() ? RegionsBackend.borderFiltered(mob, positions) : positions, weight);
    }
}
