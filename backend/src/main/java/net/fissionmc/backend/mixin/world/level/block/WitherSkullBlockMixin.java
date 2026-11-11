package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/WitherSkullBlock.java.patch (EntityConstructEvent, BUILD_WITHER spawn reason, pattern blocks cleared only after the wither was added; no spawn while block states are captured). */
@Mixin(WitherSkullBlock.class)
public abstract class WitherSkullBlockMixin {
    @Inject(method = "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private static void fission$captureCheck(final Level level, final BlockPos pos, final CallbackInfo ci) {
        if (level.captureBlockStates) ci.cancel(); // CraftBukkit
    }

    @WrapOperation(method = "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/CarvedPumpkinBlock;clearPatternBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/pattern/BlockPattern$BlockPatternMatch;)V"))
    private static void fission$moveClearDown(final Level level, final BlockPattern.BlockPatternMatch match, final Operation<Void> original) {
        // CarvedPumpkinBlock.clearPatternBlocks(level, match); // CraftBukkit - move down
    }

    @Inject(method = "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/boss/wither/WitherBoss;makeInvulnerable()V", shift = At.Shift.AFTER), cancellable = true)
    private static void fission$constructAndAdd(final Level level, final BlockPos pos, final SkullBlockEntity placedSkull, final CallbackInfo ci, @Local(ordinal = 0) final BlockPattern.BlockPatternMatch match, @Local(ordinal = 0) final WitherBoss witherBoss) {
        // Paper start
        if (!new io.papermc.paper.event.entity.EntityConstructEvent(witherBoss.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.getMatchingBlocks(level, match)).callEvent()) {
            ci.cancel();
            return;
        }
        // Paper end
        // CraftBukkit start
        if (!level.addFreshEntity(witherBoss, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_WITHER)) {
            ci.cancel();
            return;
        }
        CarvedPumpkinBlock.clearPatternBlocks(level, match); // CraftBukkit - from above
        // CraftBukkit end
    }

    @WrapOperation(method = "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean fission$addedAbove(final Level level, final Entity entity, final Operation<Boolean> original) {
        return true; // level.addFreshEntity(witherBoss); // CraftBukkit - moved up
    }
}
