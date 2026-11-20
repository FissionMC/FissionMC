package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.blocks.PaperCauldrons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: AbstractCauldronBlock.java.patch - pass the hit direction to CauldronInteraction#interact (via {@link PaperCauldrons#hitDirection}). */
@Mixin(AbstractCauldronBlock.class)
public abstract class AbstractCauldronBlockMixin {
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/cauldron/CauldronInteraction;interact(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult paper$hitDirection(final CauldronInteraction interaction, final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack stack,
                                                 final Operation<InteractionResult> original, @Local(argsOnly = true) final BlockHitResult hitResult) {
        PaperCauldrons.hitDirection = hitResult.getDirection();
        try {
            return original.call(interaction, state, level, pos, player, hand, stack);
        } finally {
            PaperCauldrons.hitDirection = null;
        }
    }
}
