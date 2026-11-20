package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.gameevent.GameEvent;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/SculkSensorBlock.java.patch */
@Mixin(SculkSensorBlock.class)
public abstract class SculkSensorBlockMixin {
    // Paper start - Call BlockRedstoneEvent (deactivate: the event may keep the sensor powered)
    @Inject(method = "deactivate", at = @At("HEAD"), cancellable = true)
    private static void paper$deactivateEvent(final Level level, final BlockPos pos, final BlockState state, final CallbackInfo ci) {
        final int newCurrent = CraftEventFactory.callRedstoneChange(level, pos, state.getValue(SculkSensorBlock.POWER), 0).getNewCurrent();
        if (newCurrent > 0) {
            level.setBlock(pos, state.setValue(SculkSensorBlock.POWER, newCurrent), Block.UPDATE_ALL);
            ci.cancel();
        }
    }

    // Paper - Call BlockRedstoneEvent (activate: body replicated, the event may change or veto the power)
    @Inject(method = "activate", at = @At("HEAD"), cancellable = true)
    private void paper$activateEvent(final @Nullable Entity sourceEntity, final Level level, final BlockPos pos, final BlockState state, final int calculatedPower, final int vibrationFrequency, final CallbackInfo ci) {
        ci.cancel();
        final int power = CraftEventFactory.callRedstoneChange(level, pos, state.getValue(SculkSensorBlock.POWER), calculatedPower).getNewCurrent();
        if (power == 0) {
            return;
        }
        final SculkSensorBlock self = (SculkSensorBlock) (Object) this;
        level.setBlockAndUpdate(pos, state.setValue(SculkSensorBlock.PHASE, SculkSensorPhase.ACTIVE).setValue(SculkSensorBlock.POWER, power));
        level.scheduleTick(pos, state.getBlock(), self.getActiveTicks());
        level.updateNeighborsAt(pos, state.getBlock()); // updateNeighbours(level, pos, state)
        level.updateNeighborsAt(pos.below(), state.getBlock());
        SculkSensorBlock.tryResonateVibration(sourceEntity, level, pos, vibrationFrequency);
        level.gameEvent(sourceEntity, GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, pos);
        if (!state.getValue(SculkSensorBlock.WATERLOGGED)) {
            level.playSound(null, (double) pos.getX() + 0.5D, (double) pos.getY() + 0.5D, (double) pos.getZ() + 0.5D, SoundEvents.SCULK_CLICKING, SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.2F + 0.8F);
        }
    }
    // Paper end

    // CraftBukkit start - Delegate to getExpDrop
    // TODO(behaviour): Paper removes the XP drop from spawnAfterBreak (see BlockMixin#getExpDrop).
    @SuppressWarnings("unused")
    public int getExpDrop(final BlockState state, final ServerLevel level, final BlockPos pos, final ItemStack tool, final boolean dropExperience) {
        if (dropExperience) {
            // FissionMC: Paper's tryDropExperience returns the amount instead of popping it
            final int experience = EnchantmentHelper.processBlockExperience(level, tool, ConstantInt.of(5).sample(level.getRandom()));
            return experience > 0 ? experience : 0;
        }
        return 0;
        // CraftBukkit end
    }
}
