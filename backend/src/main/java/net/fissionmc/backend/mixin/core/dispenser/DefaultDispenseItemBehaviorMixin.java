package net.fissionmc.backend.mixin.core.dispenser;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/core/dispenser/DefaultDispenseItemBehavior.java.patch
 * Paper splits spawnItem into prepareItem + the event-firing overload; here the vanilla spawnItem body is reused and the
 * addFreshEntity is intercepted while the BlockSource overload is running (main thread only). The facing is read from the block
 * state instead of being cached in a field.
 */
@Mixin(DefaultDispenseItemBehavior.class)
public abstract class DefaultDispenseItemBehaviorMixin {
    private static final ThreadLocal<Object[]> fission$context = new ThreadLocal<>(); // {BlockSource, Boolean result}

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void fission$execute(final BlockSource source, final ItemStack dispensed, final CallbackInfoReturnable<ItemStack> cir) {
        Direction direction = source.state().getValue(DispenserBlock.FACING);
        Position position = DispenserBlock.getDispensePosition(source);
        ItemStack itemStack = dispensed.split(1);
        if (!DefaultDispenseItemBehavior.spawnItem(source.level(), itemStack, 6, direction, position, source)) {
            dispensed.grow(1);
        }
        cir.setReturnValue(dispensed);
    }

    private static boolean spawnItem(Level level, ItemStack itemStack, int accuracy, Direction direction, Position position, BlockSource source) {
        if (itemStack.isEmpty()) return true;
        Object[] ctx = new Object[] {source, Boolean.TRUE};
        fission$context.set(ctx);
        try {
            DefaultDispenseItemBehavior.spawnItem(level, itemStack, accuracy, direction, position);
        } finally {
            fission$context.remove();
        }
        return (Boolean) ctx[1];
    }

    @WrapOperation(method = "spawnItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean fission$dispenseEvent(final Level level, final Entity entity, final Operation<Boolean> original) {
        final Object[] ctx = fission$context.get();
        if (ctx == null || !(entity instanceof ItemEntity itemEntity)) {
            return original.call(level, entity);
        }
        final BlockSource source = (BlockSource) ctx[0];
        final ItemStack itemStack = itemEntity.getItem();

        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
        org.bukkit.inventory.ItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemStack);

        org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(block, craftItem.clone(), org.bukkit.craftbukkit.util.CraftVector.toBukkit(itemEntity.getDeltaMovement()));
        level.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            ctx[1] = Boolean.FALSE;
            return false;
        }

        itemEntity.setItem(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()));
        itemEntity.setDeltaMovement(org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getVelocity()));

        if (source.state().is(net.minecraft.world.level.block.Blocks.DISPENSER) && event.getItem().getType() != craftItem.getType()) {
            ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
            DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
            ctx[1] = Boolean.FALSE;
            if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior.getClass() != DefaultDispenseItemBehavior.class) {
                dispenseBehavior.dispense(source, eventStack);
                return false;
            }
            return original.call(level, entity);
        }

        return original.call(level, entity);
    }
}
