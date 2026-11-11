package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.backend.blocks.PaperContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/level/block/ComposterBlock.java.patch
 * done: CompostItemEvent / EntityCompostItemEvent, EntityChangeBlockEvent (addLayer, extractProduce), container InventoryHolders.
 * addLayer cannot gain Paper's addedStack parameter or return null: the stack and the "cancelled" result travel in PaperContext,
 * a cancelled addLayer returns the unchanged state and each caller checks the flag right after the call.
 */
@Mixin(ComposterBlock.class)
public abstract class ComposterBlockMixin {
    @Shadow
    private static BlockState empty(final Entity sourceEntity, final BlockState state, final LevelAccessor level, final BlockPos pos) {
        throw new AssertionError();
    }

    // Paper start - call compost events
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/ComposterBlock;addLayer(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/component/Compostable;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState paper$useStack(final Entity sourceEntity, final BlockState state, final ServerLevel level, final BlockPos pos, final Compostable compostable, final Operation<BlockState> original, @Local(argsOnly = true) final ItemStack itemStack) {
        PaperContext.COMPOST_STACK.set(itemStack);
        try {
            return original.call(sourceEntity, state, level, pos, compostable);
        } finally {
            PaperContext.COMPOST_STACK.remove();
        }
    }

    @Inject(method = "useItemOn", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/world/level/block/ComposterBlock;addLayer(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/component/Compostable;)Lnet/minecraft/world/level/block/state/BlockState;"), cancellable = true)
    private void paper$useCancelled(final CallbackInfoReturnable<InteractionResult> cir) {
        if (PaperContext.takeCompostCancelled()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @WrapOperation(method = "insertItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/ComposterBlock;addLayer(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/component/Compostable;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState paper$insertStack(final Entity sourceEntity, final BlockState state, final ServerLevel level, final BlockPos pos, final Compostable compostable, final Operation<BlockState> original, @Local(argsOnly = true) final ItemStack itemStack) {
        PaperContext.COMPOST_STACK.set(itemStack);
        try {
            return original.call(sourceEntity, state, level, pos, compostable);
        } finally {
            PaperContext.COMPOST_STACK.remove();
        }
    }

    @Inject(method = "insertItem", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/world/level/block/ComposterBlock;addLayer(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/component/Compostable;)Lnet/minecraft/world/level/block/state/BlockState;"), cancellable = true)
    private static void paper$insertCancelled(final Entity sourceEntity, final BlockState state, final ServerLevel level, final ItemStack itemStack, final BlockPos pos, final CallbackInfoReturnable<BlockState> cir) {
        if (PaperContext.takeCompostCancelled()) {
            cir.setReturnValue(state);
        }
    }

    @WrapOperation(method = "addLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/providers/number/ints/ResolvableInt;get(Lnet/minecraft/world/level/storage/loot/LootContext;I)I"))
    private static int paper$compostEvent(final ResolvableInt layers, final LootContext lootContext, final int fallback, final Operation<Integer> original,
                                          @Local(argsOnly = true) final Entity sourceEntity, @Local(argsOnly = true) final ServerLevel level, @Local(argsOnly = true) final BlockPos pos) {
        int layersToAdd = original.call(layers, lootContext, fallback);
        PaperContext.COMPOST_CANCELLED.remove();
        ItemStack addedStack = PaperContext.COMPOST_STACK.get();
        if (addedStack == null) addedStack = ItemStack.EMPTY; // a caller outside the three vanilla ones
        final io.papermc.paper.event.block.CompostItemEvent event;
        if (sourceEntity == null) {
            event = new io.papermc.paper.event.block.CompostItemEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(addedStack), layersToAdd);
        } else {
            event = new io.papermc.paper.event.entity.EntityCompostItemEvent(sourceEntity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(addedStack), layersToAdd);
        }
        if (!event.callEvent()) { // check for cancellation of entity event (non entity event can't be cancelled cause of hoppers)
            PaperContext.COMPOST_CANCELLED.set(Boolean.TRUE);
            return 0; // no layer: vanilla returns the unchanged state
        }
        return event.getLevelsToRaise();
    }
    // Paper end - call compost events

    // Paper start - call EntityChangeBlockEvent
    @Inject(method = "addLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"), cancellable = true)
    private static void paper$addLayerChangeBlock(final Entity sourceEntity, final BlockState state, final ServerLevel level, final BlockPos pos, final Compostable compostable, final CallbackInfoReturnable<BlockState> cir, @Local(ordinal = 1) final BlockState newState) {
        if (sourceEntity != null && !org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(sourceEntity, pos, newState)) {
            PaperContext.COMPOST_CANCELLED.set(Boolean.TRUE);
            cir.setReturnValue(state);
        }
    }
    // Paper end - call EntityChangeBlockEvent

    // CraftBukkit start
    @Inject(method = "extractProduce", at = @At("HEAD"), cancellable = true)
    private static void paper$extractChangeBlock(final Entity sourceEntity, final BlockState state, final Level level, final BlockPos pos, final CallbackInfoReturnable<BlockState> cir) {
        if (!(sourceEntity instanceof Player)) {
            BlockState emptyState = empty(sourceEntity, state, org.bukkit.craftbukkit.util.DummyLevelAccessor.INSTANCE, pos);
            if (!org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(sourceEntity, pos, emptyState)) {
                cir.setReturnValue(state);
            }
        }
    }

    // empty levelAccessor, blockPos: the three containers get their holder here (Paper sets it in their constructors)
    @ModifyReturnValue(method = "getContainer", at = @At("RETURN"))
    private WorldlyContainer paper$containerOwner(final WorldlyContainer container, final BlockState state, final LevelAccessor level, final BlockPos pos) {
        if (container instanceof net.minecraft.world.SimpleContainer simple) {
            simple.bukkitOwner = new org.bukkit.craftbukkit.inventory.CraftBlockInventoryHolder(level, pos, simple);
        }
        return container;
    }
    // CraftBukkit end
}
