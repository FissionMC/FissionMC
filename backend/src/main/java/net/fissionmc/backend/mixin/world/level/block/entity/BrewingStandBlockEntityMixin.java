package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BrewingStandBlockEntity.java.patch
 * done: BrewingStandFuelEvent (HEAD inject fires + cancels, getUses/shrink wrapped), BrewingStartEvent, BrewEvent (loop writes captured, event fired before the ingredient is consumed).
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {
    @Shadow private net.minecraft.core.NonNullList<ItemStack> items;

    // CraftBukkit start - add fields and methods
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;

    public java.util.List<ItemStack> getContents() {
        return this.items;
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
    }

    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    // CraftBukkit end

    @org.spongepowered.asm.mixin.Shadow protected abstract int getUses(net.minecraft.server.level.ServerLevel level, net.minecraft.world.item.component.BrewingFuel fuel);
    @org.spongepowered.asm.mixin.Shadow private int brewTime;
    @org.spongepowered.asm.mixin.Shadow int totalBrewTime;

    // Paper start - call BrewingStandFuelEvent: fired at HEAD with the same condition as the fuel branch; cancelling ends the tick like Paper's early return
    private static final ThreadLocal<org.bukkit.event.inventory.BrewingStandFuelEvent[]> paper$fuelEvent = ThreadLocal.withInitial(() -> new org.bukkit.event.inventory.BrewingStandFuelEvent[1]);

    @org.spongepowered.asm.mixin.injection.Inject(method = "serverTick", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void paper$fuelEventHead(final net.minecraft.server.level.ServerLevel level, final net.minecraft.core.BlockPos pos, final net.minecraft.world.level.block.state.BlockState state, final BrewingStandBlockEntity entity, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        paper$fuelEvent.get()[0] = null;
        final BrewingStandBlockEntityMixin self = (BrewingStandBlockEntityMixin) (Object) entity;
        final ItemStack fuel = self.items.get(4);
        final net.minecraft.world.item.component.BrewingFuel brewingFuel = fuel.get(net.minecraft.core.component.DataComponents.BREWING_FUEL);
        if (entity.fuel <= 0 && brewingFuel != null) {
            final org.bukkit.event.inventory.BrewingStandFuelEvent event = new org.bukkit.event.inventory.BrewingStandFuelEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(fuel), self.getUses(level, brewingFuel));
            if (!event.callEvent()) {
                ci.cancel();
                return;
            }
            paper$fuelEvent.get()[0] = event;
        }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "serverTick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BrewingStandBlockEntity;getUses(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/component/BrewingFuel;)I"))
    private static int paper$fuelPower(final BrewingStandBlockEntity entity, final net.minecraft.server.level.ServerLevel level, final net.minecraft.world.item.component.BrewingFuel fuel, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
        final org.bukkit.event.inventory.BrewingStandFuelEvent event = paper$fuelEvent.get()[0];
        return event != null ? event.getFuelPower() : original.call(entity, level, fuel);
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "serverTick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private static void paper$fuelConsuming(final ItemStack stack, final int amount, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final BrewingStandBlockEntity entity) {
        final org.bukkit.event.inventory.BrewingStandFuelEvent event = paper$fuelEvent.get()[0];
        if (event == null || (entity.fuel > 0 && event.isConsuming())) original.call(stack, amount);
    }

    // Paper's isConsuming guard also covers the crafting remainder: no remainder when the fuel is not consumed
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "serverTick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;"))
    private static net.minecraft.world.item.ItemStackTemplate paper$fuelRemainder(final net.minecraft.world.item.Item item, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.item.ItemStackTemplate> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final BrewingStandBlockEntity entity) {
        final org.bukkit.event.inventory.BrewingStandFuelEvent event = paper$fuelEvent.get()[0];
        return event == null || (entity.fuel > 0 && event.isConsuming()) ? original.call(item) : null;
    }
    // Paper end - call BrewingStandFuelEvent

    // Paper start - call BrewingStartEvent: vanilla has just assigned brewTime = ceil(400 / speed) and totalBrewTime
    @org.spongepowered.asm.mixin.injection.Inject(method = "serverTick", at = @org.spongepowered.asm.mixin.injection.At(value = "FIELD", target = "Lnet/minecraft/world/level/block/entity/BrewingStandBlockEntity;totalBrewTime:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = org.spongepowered.asm.mixin.injection.At.Shift.AFTER))
    private static void paper$startEvent(final net.minecraft.server.level.ServerLevel level, final net.minecraft.core.BlockPos pos, final net.minecraft.world.level.block.state.BlockState state, final BrewingStandBlockEntity entity, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        final BrewingStandBlockEntityMixin self = (BrewingStandBlockEntityMixin) (Object) entity;
        final org.bukkit.event.block.BrewingStartEvent event = new org.bukkit.event.block.BrewingStartEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(self.items.get(3)), self.brewTime);
        event.callEvent();
        self.brewTime = event.getBrewingTime();
        self.totalBrewTime = event.getRecipeBrewTime();
    }
    // Paper end - call BrewingStartEvent

    // Paper start - call BrewEvent: the loop's three potion writes are captured, the event fires before the ingredient is consumed
    private static final ThreadLocal<ItemStack[]> paper$brewResults = ThreadLocal.withInitial(() -> new ItemStack[3]);

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "doBrew", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/core/NonNullList;set(ILjava/lang/Object;)Ljava/lang/Object;"))
    private static Object paper$captureBrewResult(final net.minecraft.core.NonNullList<ItemStack> items, final int index, final Object value, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Object> original) {
        if (index < 3) {
            paper$brewResults.get()[index] = (ItemStack) value;
            return items.get(index);
        }
        return original.call(items, index, value);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "doBrew", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;"), cancellable = true)
    private static void paper$brewEvent(final net.minecraft.server.level.ServerLevel level, final net.minecraft.core.BlockPos pos, final BrewingStandBlockEntity entity, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        final BrewingStandBlockEntityMixin self = (BrewingStandBlockEntityMixin) (Object) entity;
        final ItemStack[] captured = paper$brewResults.get();
        final java.util.List<org.bukkit.inventory.ItemStack> brewResults = new java.util.ArrayList<>(3);
        for (int i = 0; i < 3; i++) brewResults.add(org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(captured[i]));
        final org.bukkit.inventory.InventoryHolder owner = entity.getOwner();
        if (owner != null) {
            final org.bukkit.event.inventory.BrewEvent event = new org.bukkit.event.inventory.BrewEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), (org.bukkit.inventory.BrewerInventory) owner.getInventory(), brewResults, entity.fuel);
            if (!event.callEvent()) {
                java.util.Arrays.fill(captured, null);
                ci.cancel();
                return;
            }
            for (int dest = 0; dest < 3; dest++) {
                self.items.set(dest, dest < brewResults.size() ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(brewResults.get(dest)) : ItemStack.EMPTY);
            }
        } else {
            for (int dest = 0; dest < 3; dest++) self.items.set(dest, captured[dest]);
        }
        java.util.Arrays.fill(captured, null);
    }
    // Paper end - call BrewEvent
}
