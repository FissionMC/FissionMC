package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/CarvedPumpkinBlock.java.patch (EntityConstructEvent, BUILD_SNOWMAN / BUILD_IRONGOLEM / BUILD_COPPERGOLEM spawn reasons; spawnGolemInWorld: replaced body). */
@Mixin(CarvedPumpkinBlock.class)
public abstract class CarvedPumpkinBlockMixin {
    @Inject(method = "spawnGolemInWorld", at = @At("HEAD"), cancellable = true)
    private static void fission$spawnGolemInWorld(final Level level, final BlockPattern.BlockPatternMatch match, final Entity golem, final BlockPos spawnPos, final CallbackInfo ci) {
        ci.cancel();
        // clearPatternBlocks(level, patternMatch); // Paper - moved down
        golem.snapTo(spawnPos.getX() + 0.5, spawnPos.getY() + 0.05, spawnPos.getZ() + 0.5, 0.0F, 0.0F);
        // Paper start
        if (!new io.papermc.paper.event.entity.EntityConstructEvent(golem.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.getMatchingBlocks(level, match)).callEvent()) {
            return;
        }
        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason spawnReason;
        if (golem.getType() == net.minecraft.world.entity.EntityTypes.SNOW_GOLEM) {
            spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN;
        } else if (golem.getType() == net.minecraft.world.entity.EntityTypes.COPPER_GOLEM) {
            spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_COPPERGOLEM;
        } else {
            spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM;
        }
        if (!level.addFreshEntity(golem, spawnReason)) {
            return;
        }
        // Paper end
        CarvedPumpkinBlock.clearPatternBlocks(level, match); // Paper - from above

        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, golem.getBoundingBox().inflate(5.0))) {
            CriteriaTriggers.SUMMONED_ENTITY.trigger(player, golem);
        }

        CarvedPumpkinBlock.updatePatternBlocks(level, match);
    }

    @Inject(method = "trySpawnGolem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/CarvedPumpkinBlock;replaceCopperBlockWithChest(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/pattern/BlockPattern$BlockPatternMatch;)V"), cancellable = true)
    private void fission$copperGolemNotAdded(final Level level, final BlockPos pos, final CallbackInfo ci, @Local(ordinal = 0) final CopperGolem copperGolem) {
        if (!copperGolem.valid) ci.cancel(); // Paper - entityspawnevent - entity was not added to the world so prevent world mutation
    }
}
