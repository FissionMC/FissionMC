package net.fissionmc.backend.mixin.world.level.block.entity;

import java.util.List;
import net.minecraft.core.BlockPos;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/ConduitBlockEntity.java.patch
 * TODO(behaviour): applyEffects split into getRange + applyEffects(level, pos, range), EntityPotionEffectEvent cause, ConduitDamage.
 */
@Mixin(ConduitBlockEntity.class)
public abstract class ConduitBlockEntityMixin {
    // CraftBukkit start
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static int getRange(List<BlockPos> effectBlocks) {
        // CraftBukkit end
        int activeSize = effectBlocks.size();
        int effectRange = activeSize / 7 * 16;
        // CraftBukkit start
        return effectRange;
    }
    // CraftBukkit end

    @Shadow
    private static EntityReference<LivingEntity> updateDestroyTarget(final EntityReference<LivingEntity> target, final ServerLevel level, final BlockPos pos, final boolean isActive) {
        throw new AssertionError();
    }

    // CraftBukkit start - add "damageTarget" boolean
    // Surface: public static (see surface/members/blocks.txt); the loader widens it.
    // TODO(behaviour): the vanilla 5-arg updateAndAttackTarget should delegate here with damageTarget = true.
    private static void updateAndAttackTarget(ServerLevel level, BlockPos worldPosition, BlockState blockState, ConduitBlockEntity entity, boolean isActive, boolean damageTarget) {
        // CraftBukkit end - add "damageTarget" boolean
        EntityReference<LivingEntity> newDestroyTarget = updateDestroyTarget(entity.destroyTarget, level, worldPosition, isActive);
        LivingEntity targetEntity = EntityReference.getLivingEntity(newDestroyTarget, level);
        if (damageTarget && targetEntity != null) { // CraftBukkit
            if (targetEntity.hurtServer(level, level.damageSources().magic().eventBlockDamager(level, worldPosition), 4.0F)) // CraftBukkit - move up
            level.playSound(
                null, targetEntity.getX(), targetEntity.getY(), targetEntity.getZ(), SoundEvents.CONDUIT_ATTACK_TARGET, SoundSource.BLOCKS, 1.0F, 1.0F
            );
        }

        if (!Objects.equals(newDestroyTarget, entity.destroyTarget)) {
            entity.destroyTarget = newDestroyTarget;
            level.sendBlockUpdated(worldPosition, blockState, blockState, 2);
        }
    }
}
