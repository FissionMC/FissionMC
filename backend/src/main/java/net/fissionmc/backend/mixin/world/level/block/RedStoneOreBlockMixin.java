package net.fissionmc.backend.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.blocks.PaperGrowth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/level/block/RedStoneOreBlock.java.patch */
@Mixin(RedStoneOreBlock.class)
public abstract class RedStoneOreBlockMixin {
    private static final String INTERACT = "Lnet/minecraft/world/level/block/RedStoneOreBlock;interact(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V";

    // CraftBukkit - add player / entity to interact()
    @WrapOperation(method = "attack", at = @At(value = "INVOKE", target = INTERACT))
    private void paper$attackInteract(final BlockState state, final Level level, final BlockPos pos, final Operation<Void> original, @Local(argsOnly = true) final Player player) {
        PaperGrowth.interactor = player;
        try {
            original.call(state, level, pos);
        } finally {
            PaperGrowth.interactor = null;
        }
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = INTERACT))
    private void paper$useInteract(final BlockState state, final Level level, final BlockPos pos, final Operation<Void> original, @Local(argsOnly = true) final Player player) {
        PaperGrowth.interactor = player;
        try {
            original.call(state, level, pos);
        } finally {
            PaperGrowth.interactor = null;
        }
    }

    // CraftBukkit start - PlayerInteractEvent (PHYSICAL) / EntityInteractEvent when stepping on the ore
    @WrapOperation(method = "stepOn", at = @At(value = "INVOKE", target = INTERACT))
    private void paper$stepInteract(final BlockState state, final Level level, final BlockPos pos, final Operation<Void> original, @Local(argsOnly = true) final Entity entity) {
        if (entity instanceof Player player) {
            if (CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null).isCancelled()) {
                return;
            }
        } else {
            final org.bukkit.event.entity.EntityInteractEvent event = new org.bukkit.event.entity.EntityInteractEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos));
            if (!event.callEvent()) {
                return;
            }
        }
        PaperGrowth.interactor = entity;
        try {
            original.call(level.getBlockState(pos), level, pos);
        } finally {
            PaperGrowth.interactor = null;
        }
    }
    // CraftBukkit end

    // CraftBukkit - EntityChangeBlockEvent when the ore lights up
    @WrapOperation(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private static boolean paper$lightEvent(final Level level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        final Entity entity = PaperGrowth.interactor;
        if (entity != null && !CraftEventFactory.callEntityChangeBlockEvent(entity, pos, newState)) {
            return false;
        }
        return original.call(level, pos, newState);
    }

    // CraftBukkit - BlockFadeEvent when the ore turns off
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$fadeEvent(final ServerLevel level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        if (CraftEventFactory.callBlockFadeEvent(level, pos, newState).isCancelled()) {
            return false;
        }
        return original.call(level, pos, newState);
    }

    // CraftBukkit start - Delegate to getExpDrop
    // TODO(behaviour): Paper removes the XP drop from spawnAfterBreak (see BlockMixin#getExpDrop).
    @SuppressWarnings("unused")
    public int getExpDrop(final BlockState state, final ServerLevel level, final BlockPos pos, final ItemStack tool, final boolean dropExperience) {
        if (dropExperience) {
            // FissionMC: Paper's tryDropExperience returns the amount instead of popping it
            final int experience = EnchantmentHelper.processBlockExperience(level, tool, UniformInt.of(1, 5).sample(level.getRandom()));
            return experience > 0 ? experience : 0;
        }
        return 0;
        // CraftBukkit end
    }
}
