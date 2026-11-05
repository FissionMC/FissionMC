package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.redstone.Orientation;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/PressurePlateBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(PressurePlateBlock.class)
public abstract class PressurePlateBlockMixin {
    @Shadow protected abstract int getSignalForState(BlockState state);

    // CraftBukkit start - Call interact event when turning on a pressure plate
    @WrapOperation(method = "getSignalStrength", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/PressurePlateBlock;getEntityCount(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/AABB;Ljava/lang/Class;)I"))
    private int fission$interact(final Level level, final net.minecraft.world.phys.AABB box, final Class<? extends Entity> entityClass, final Operation<Integer> original, final Level level2, final BlockPos pos) {
        for (Entity entity : level.getEntitiesOfClass(entityClass, box, net.minecraft.world.entity.EntitySelector.NO_SPECTATORS.and(e -> !e.isIgnoringBlockTriggers()))) {
            if (this.getSignalForState(level.getBlockState(pos)) == 0) {
                org.bukkit.event.Cancellable cancellable;

                if (entity instanceof net.minecraft.world.entity.player.Player player) {
                    cancellable = CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null);
                } else {
                    cancellable = new org.bukkit.event.entity.EntityInteractEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos));
                    level.getCraftServer().getPluginManager().callEvent((org.bukkit.event.entity.EntityInteractEvent) cancellable);
                }

                // We only want to block turning the plate on if all events are cancelled
                if (cancellable.isCancelled()) {
                    continue;
                }
            }

            return 1; // return 15;
        }

        return 0;
        // CraftBukkit end
    }
}
