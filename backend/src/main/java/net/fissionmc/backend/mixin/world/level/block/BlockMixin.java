package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.fissionmc.backend.blocks.PaperBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/block/Block.java.patch */
@Mixin(Block.class)
public abstract class BlockMixin {
    // Surface: public static final (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static final int UPDATE_SKIP_POI = 4096; // Paper - temporary flag until ItemStack cleanup is merged
    // TODO(behaviour): Level#setBlock / PoiManager honour UPDATE_SKIP_POI (server area)

    // Paper start - Protect Bedrock and End Portal/Frames from being destroyed
    public final boolean isDestroyable() {
        final Block self = (Block) (Object) this;
        return io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowPermanentBlockBreakExploits ||
            self != Blocks.BARRIER &&
            self != Blocks.BEDROCK &&
            self != Blocks.END_PORTAL_FRAME &&
            self != Blocks.END_PORTAL &&
            self != Blocks.END_GATEWAY &&
            self != Blocks.COMMAND_BLOCK &&
            self != Blocks.REPEATING_COMMAND_BLOCK &&
            self != Blocks.CHAIN_COMMAND_BLOCK &&
            self != Blocks.STRUCTURE_BLOCK &&
            self != Blocks.JIGSAW;
    }
    // Paper end - Protect Bedrock and End Portal/Frames from being destroyed

    // Paper start - Add BlockBreakBlockEvent
    // Surface: public static; the loader widens it.
    private static boolean dropResources(final BlockState state, final Level level, final BlockPos pos, final @Nullable BlockEntity blockEntity, final BlockPos source) {
        if (level instanceof ServerLevel serverLevel) {
            List<org.bukkit.inventory.ItemStack> items = new java.util.ArrayList<>();
            for (ItemStack drop : Block.getDrops(state, serverLevel, pos, blockEntity)) {
                items.add(org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(drop));
            }
            Block block = state.getBlock(); // Paper - Properly handle xp dropping
            io.papermc.paper.event.block.BlockBreakBlockEvent event = new io.papermc.paper.event.block.BlockBreakBlockEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), org.bukkit.craftbukkit.block.CraftBlock.at(level, source), items);
            event.setExpToDrop(block.getExpDrop(state, serverLevel, pos, net.minecraft.world.item.ItemStack.EMPTY, true)); // Paper - Properly handle xp dropping
            event.callEvent();
            for (org.bukkit.inventory.ItemStack drop : event.getDrops()) {
                Block.popResource(serverLevel, pos, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(drop));
            }
            state.spawnAfterBreak(serverLevel, pos, ItemStack.EMPTY, false); // Paper - Properly handle xp dropping
            block.popExperience(serverLevel, pos, event.getExpToDrop(), null); // Paper - Properly handle xp dropping (FissionMC: public overload, same body)
        }
        return true;
    }
    // Paper end - Add BlockBreakBlockEvent

    // Paper start - Properly handle xp dropping
    // Surface: public static (see surface/members/blocks.txt); the loader widens it.
    private static void dropResources(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final @Nullable BlockEntity blockEntity,
        final @Nullable Entity breaker,
        final ItemStack tool
        , final boolean dropExperience // Paper - Properly handle xp dropping
    ) {
        if (level instanceof ServerLevel serverLevel) {
            Block.getDrops(state, serverLevel, pos, blockEntity, breaker, tool).forEach(stack -> Block.popResource(level, pos, stack));
            state.spawnAfterBreak(serverLevel, pos, tool, dropExperience); // Paper - Properly handle xp dropping
        }
    }
    // Paper end - Properly handle xp dropping

    // CraftBukkit start
    @WrapOperation(method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean fission$captureDrops(final Level level, final Entity entity, final Operation<Boolean> original) {
        if (level.captureDrops != null) {
            level.captureDrops.add((ItemEntity) entity);
            return true;
        } else {
            return original.call(level, entity);
        }
    }
    // CraftBukkit end

    // Paper start - add entity parameter
    @Inject(method = "popExperience", at = @At("HEAD"), cancellable = true)
    private void fission$popExperience(final ServerLevel level, final BlockPos pos, final int amount, final CallbackInfo ci) {
        this.popExperience(level, pos, amount, null);
        ci.cancel();
    }

    public void popExperience(ServerLevel level, BlockPos pos, int amount, net.minecraft.world.entity.@Nullable Entity entity) {
        // Paper end - add entity parameter
        if (level.getGameRules().get(GameRules.BLOCK_DROPS)) {
            net.minecraft.world.entity.ExperienceOrb.awardWithDirection(level, Vec3.atCenterOf(pos), net.minecraft.world.phys.Vec3.ZERO, amount, org.bukkit.entity.ExperienceOrb.SpawnReason.BLOCK_BREAK, entity, null); // Paper
        }
    }

    // Paper start - fix drops not preventing stats/food exhaustion
    // Vanilla subclasses override the 6-argument playerDestroy; the flags reach Block's own body through PaperBlocks.
    public void playerDestroy(final ServerLevel level, final ServerPlayer player, final BlockPos pos, final BlockState state, final @Nullable BlockEntity blockEntity, final ItemStack destroyedWith, final boolean includeDrops, final boolean dropExp) {
        final boolean prevDrops = PaperBlocks.playerDestroyIncludeDrops;
        final boolean prevExp = PaperBlocks.playerDestroyDropExp;
        PaperBlocks.playerDestroyIncludeDrops = includeDrops;
        PaperBlocks.playerDestroyDropExp = dropExp;
        try {
            ((Block) (Object) this).playerDestroy(level, player, pos, state, blockEntity, destroyedWith);
        } finally {
            PaperBlocks.playerDestroyIncludeDrops = prevDrops;
            PaperBlocks.playerDestroyDropExp = prevExp;
        }
    }

    @WrapOperation(method = "playerDestroy", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;causeFoodExhaustion(F)V"))
    private void fission$exhaustionReason(final ServerPlayer player, final float amount, final Operation<Void> original) {
        player.causeFoodExhaustion(amount, org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.BLOCK_MINED); // CraftBukkit - EntityExhaustionEvent
    }

    @WrapOperation(method = "playerDestroy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V"))
    private void fission$playerDestroyDrops(final BlockState state, final Level level, final BlockPos pos, final BlockEntity blockEntity, final Entity breaker, final ItemStack tool, final Operation<Void> original) {
        if (PaperBlocks.playerDestroyIncludeDrops) { // Paper - fix drops not preventing stats/food exhaustion
            dropResources(state, level, pos, blockEntity, breaker, tool, PaperBlocks.playerDestroyDropExp); // Paper - Properly handle xp dropping
        } // Paper - fix drops not preventing stats/food exhaustion
    }
    // Paper end - fix drops not preventing stats/food exhaustion

    // CraftBukkit start - tryDropExperience returns the amount instead of popping it; BlockStateBase#spawnAfterBreak pops getExpDrop
    @Inject(method = "tryDropExperience", at = @At("HEAD"), cancellable = true)
    private void fission$tryDropExperience(final ServerLevel level, final BlockPos pos, final ItemStack tool, final IntProvider xpRange, final CallbackInfo ci) {
        ci.cancel(); // this.popExperience(level, pos, experience);
    }

    public int getExpDrop(final BlockState state, final ServerLevel level, final BlockPos pos, final ItemStack tool, final boolean dropExperience) {
        return 0;
    }
    // CraftBukkit end
}
