package net.fissionmc.backend.mixin.world.inventory;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/inventory/AnvilMenu.java.patch */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin extends ItemCombinerMenu {
    @Shadow @Final private DataSlot cost;

    // CraftBukkit start
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private static final int DEFAULT_DENIED_COST = -1;
    private int maximumRepairCost = 40;
    private org.bukkit.craftbukkit.inventory.view.CraftAnvilView bukkitEntity;
    // CraftBukkit end
    private boolean bypassEnchantmentLevelRestriction = false; // Paper - bypass anvil level restrictions

    private AnvilMenuMixin() {
        super(null, 0, null, null, null);
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void fission$mayPickup(final Player player, final boolean hasItem, final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue((player.hasInfiniteMaterials() || player.experienceLevel >= this.cost.get()) && this.cost.get() > AnvilMenu.DEFAULT_DENIED_COST && hasItem); // CraftBukkit - allow cost 0 like a free item
    }

    @ModifyArg(method = "onTake", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/DataSlot;set(I)V"))
    private int fission$onTakeDeniedCost(final int value) {
        return value == 0 ? AnvilMenu.DEFAULT_DENIED_COST : value; // CraftBukkit - use a variable for set a cost for denied item
    }

    // Paper start - AnvilDamageEvent
    @WrapOperation(method = "lambda$onTake$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/AnvilBlock;damage(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState fission$anvilDamage(final BlockState state, final Operation<BlockState> original, final Player player, final Level level, final BlockPos pos,
                                                  @Share("cancelled") final LocalBooleanRef cancelled) {
        BlockState newBlockState = original.call(state);
        // FissionMC: the vanilla lambda is static; the anvil menu is the player's open menu while taking the result
        com.destroystokyo.paper.event.block.AnvilDamagedEvent event = new com.destroystokyo.paper.event.block.AnvilDamagedEvent(player.containerMenu.getBukkitView(), newBlockState != null ? newBlockState.asBlockData() : null);
        if (!event.callEvent()) {
            cancelled.set(true);
            return newBlockState;
        } else if (event.getDamageState() == com.destroystokyo.paper.event.block.AnvilDamagedEvent.DamageState.BROKEN) {
            newBlockState = null;
        } else {
            newBlockState = ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getDamageState().getMaterial().createBlockData()).getState().setValue(AnvilBlock.FACING, state.getValue(AnvilBlock.FACING));
        }
        return newBlockState;
    }

    @Inject(method = "lambda$onTake$0", cancellable = true, at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
        @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z")
    })
    private static void fission$anvilDamageCancelled(final Player player, final Level level, final BlockPos pos, final CallbackInfo ci, @Share("cancelled") final LocalBooleanRef cancelled) {
        if (cancelled.get()) ci.cancel();
    }
    // Paper end - AnvilDamageEvent

    // CraftBukkit start - PrepareAnvilEvent
    @WrapOperation(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private void fission$prepareAnvil(final ResultContainer resultSlots, final int slot, final ItemStack result, final Operation<Void> original) {
        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareAnvilEvent(this.getBukkitView(), result); // CraftBukkit
    }

    @ModifyArg(method = "createResult", slice = @org.spongepowered.asm.mixin.injection.Slice(from = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/world/inventory/DataSlot;set(I)V"), to = @At(value = "INVOKE", ordinal = 4, target = "Lnet/minecraft/world/inventory/DataSlot;set(I)V")),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/DataSlot;set(I)V"))
    private int fission$deniedCost(final int value) {
        return value == 0 ? AnvilMenu.DEFAULT_DENIED_COST : value; // CraftBukkit - use a variable for set a cost for denied item
    }

    @ModifyConstant(method = "createResult", constant = @Constant(intValue = 40, ordinal = 1))
    private int fission$maxCost1(final int original) {
        return this.maximumRepairCost; // CraftBukkit
    }

    @ModifyConstant(method = "createResult", constant = @Constant(intValue = 39))
    private int fission$maxCostMinusOne(final int original) {
        return this.maximumRepairCost - 1; // CraftBukkit
    }

    @ModifyConstant(method = "createResult", constant = @Constant(intValue = 40, ordinal = 2))
    private int fission$maxCost2(final int original) {
        return this.maximumRepairCost; // CraftBukkit
    }

    @ModifyExpressionValue(method = "createResult", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/world/item/enchantment/Enchantment;getMaxLevel()I"))
    private int fission$bypassLevel(final int maxLevel) {
        return this.bypassEnchantmentLevelRestriction ? Integer.MAX_VALUE : maxLevel; // Paper - bypass anvil level restrictions
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void fission$syncResult(final CallbackInfo ci) {
        this.sendAllDataToRemote(); // CraftBukkit - SPIGOT-6686, SPIGOT-7931: Always send completed inventory to stay in sync with client
    }
    // CraftBukkit end

    @Inject(method = "setItemName", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/world/inventory/AnvilMenu;createResult()V"))
    private void fission$prepareResult(final String name, final CallbackInfoReturnable<Boolean> cir) {
        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareResultEvent(this, AnvilMenu.RESULT_SLOT); // Paper - Add PrepareResultEvent
    }

    // CraftBukkit start
    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftAnvilView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryAnvil inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryAnvil(
                this.access.getLocation(), this.inputSlots, this.resultSlots);
        this.bukkitEntity = new org.bukkit.craftbukkit.inventory.view.CraftAnvilView(this.player.getBukkitEntity(), inventory, (AnvilMenu) (Object) this);
        this.bukkitEntity.updateFromLegacy(inventory);
        return this.bukkitEntity;
    }
    // CraftBukkit end
}
