package net.fissionmc.backend.mixin.world.entity.vehicle.minecart;

import net.fissionmc.backend.entitytypes.ContainerEntityPaper;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;

/** Paper: sources/net/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer.java.patch (+ ContainerEntity.java.patch) */
@Mixin(AbstractMinecartContainer.class)
public abstract class AbstractMinecartContainerMixin extends AbstractMinecart {
    @Shadow private NonNullList<ItemStack> itemStacks;

    protected AbstractMinecartContainerMixin(final EntityType<?> type, final Level level) {
        super(type, level);
    }

    // Paper start - LootTable API
    private final com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData = new com.destroystokyo.paper.loottable.PaperLootableInventoryData();

    public com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData() {
        return this.lootableData;
    }
    // Paper end - LootTable API

    // CraftBukkit start - add Bukkit remove cause
    @Inject(method = "remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("HEAD"), cancellable = true)
    private void fission$removeDelegate(Entity.RemovalReason reason, CallbackInfo ci) {
        ci.cancel();
        this.remove(reason, null);
    }

    @Override
    public void remove(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) {
        if (!this.level().isClientSide() && reason.shouldDestroy()) {
            Containers.dropContents(this.level(), this, (AbstractMinecartContainer) (Object) this);
        }

        super.remove(reason, cause);
    }
    // CraftBukkit end

    // Paper - ContainerEntity.java.patch hunks (FissionMC: the defaults are replaced at their call sites)
    @Redirect(method = "addAdditionalSaveData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer;addChestVehicleSaveData(Lnet/minecraft/world/level/storage/ValueOutput;)V"))
    private void fission$save(AbstractMinecartContainer instance, ValueOutput output) {
        ContainerEntityPaper.addChestVehicleSaveData(instance, output);
    }

    @Redirect(method = "readAdditionalSaveData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer;readChestVehicleSaveData(Lnet/minecraft/world/level/storage/ValueInput;)V"))
    private void fission$read(AbstractMinecartContainer instance, ValueInput input) {
        ContainerEntityPaper.readChestVehicleSaveData(instance, input);
    }

    @Redirect(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer;interactWithContainerVehicle(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult fission$interact(AbstractMinecartContainer instance, Player player) {
        return ContainerEntityPaper.interactWithContainerVehicle(instance, player);
    }

    @Redirect(method = "createMenu", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer;unpackChestVehicleLootTable(Lnet/minecraft/world/entity/player/Player;)V"))
    private void fission$unpack(AbstractMinecartContainer instance, @Nullable Player player) {
        ContainerEntityPaper.unpackChestVehicleLootTable(instance, player);
    }

    // CraftBukkit start
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;

    public java.util.List<net.minecraft.world.item.ItemStack> getContents() {
        return this.itemStacks;
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

    public org.bukkit.inventory.@Nullable InventoryHolder getOwner() {
        org.bukkit.entity.Entity entity = this.getBukkitEntity();
        return entity instanceof final org.bukkit.inventory.InventoryHolder inventoryHolder ? inventoryHolder : null;
    }

    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    public org.bukkit.Location getLocation() {
        return this.getBukkitEntity().getLocation();
    }
    // CraftBukkit end

    @ModifyConstant(method = "<init>*", constant = @Constant(intValue = 36))
    private int fission$containerSize(int original) {
        return ((AbstractMinecartContainer) (Object) this).getContainerSize(); // CraftBukkit - SPIGOT-3513
    }
}
