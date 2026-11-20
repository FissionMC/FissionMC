package net.fissionmc.backend.mixin.core.dispenser;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperDispense;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: DispenseItemBehavior.java.patch - empty bucket pickup: the first pickupBlock runs against a dummy level accessor (so a cancelled
 * BlockDispenseEvent leaves the fluid in place), the real pickup happens after the event.
 */
@Mixin(targets = "net.minecraft.core.dispenser.DispenseItemBehavior$4")
public abstract class DispenseItemBehavior_4Mixin {
    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack paper$dummyPickup(final BucketPickup bucket, final LivingEntity user, final LevelAccessor level, final BlockPos pos, final BlockState state, final Operation<ItemStack> original) {
        return original.call(bucket, user, org.bukkit.craftbukkit.util.DummyLevelAccessor.INSTANCE, pos, state); // CraftBukkit
    }

    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;gameEvent(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)V"), cancellable = true)
    private void paper$dispenseEvent(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack result = PaperDispense.fire(source, dispensed, this);
        if (result != null) {
            cir.setReturnValue(result);
            return;
        }
        // CraftBukkit - the real pickup, after the event
        final BlockPos target = PaperDispense.target(source);
        final BlockState blockState = source.level().getBlockState(target);
        if (blockState.getBlock() instanceof BucketPickup bucket) {
            bucket.pickupBlock(null, source.level(), target, blockState);
        }
    }
}
