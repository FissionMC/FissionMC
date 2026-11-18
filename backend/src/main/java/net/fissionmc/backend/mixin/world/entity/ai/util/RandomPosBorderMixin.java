package net.fissionmc.backend.mixin.world.entity.ai.util;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.Supplier;
import net.fissionmc.backend.relay.RegionsBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.RandomPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FissionMC static regions, mob AI border: every random-position goal (wander, panic, avoid entity, flee, fly/swim/hover) draws its candidates through this method
 * (DefaultRandomPos, LandRandomPos, AirAndWaterRandomPos, HoverRandomPos, AirRandomPos). Candidates the mob may not target ([RegionsBackend#mobMayTarget]) are replaced by null,
 * which the search skips, so the mob still finds a position on its own side. New file.
 */
@Mixin(RandomPos.class)
public abstract class RandomPosBorderMixin {
    @WrapMethod(method = "generateRandomPos(Lnet/minecraft/world/entity/PathfinderMob;Ljava/util/function/Supplier;)Lnet/minecraft/world/phys/Vec3;")
    private static Vec3 fission$noPositionsAcrossTheBorder(final PathfinderMob mob, final Supplier<BlockPos> positions, final Operation<Vec3> original) {
        return original.call(mob, RegionsBackend.mobAiBorderActive() ? RegionsBackend.borderFiltered(mob, positions) : positions);
    }
}
