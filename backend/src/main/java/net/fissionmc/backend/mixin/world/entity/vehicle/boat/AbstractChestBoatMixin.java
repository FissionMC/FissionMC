package net.fissionmc.backend.mixin.world.entity.vehicle.boat;

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
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.level.gameevent.GameEvent;

/** Paper: sources/net/minecraft/world/entity/vehicle/boat/AbstractChestBoat.java.patch (+ ContainerEntity.java.patch) */
@Mixin(AbstractChestBoat.class)
public abstract class AbstractChestBoatMixin extends AbstractBoat {
    @Shadow private NonNullList<ItemStack> itemStacks;

    protected AbstractChestBoatMixin(final EntityType<?> type, final Level level) {
        super(null, level, null); // FissionMC: mixin constructor body is never executed
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
            Containers.dropContents(this.level(), this, (AbstractChestBoat) (Object) this);
        }

        super.remove(reason, cause);
    }
    // CraftBukkit end

    // Paper - ContainerEntity.java.patch hunks (FissionMC: the defaults are replaced at their call sites)
    @Redirect(method = "addAdditionalSaveData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractChestBoat;addChestVehicleSaveData(Lnet/minecraft/world/level/storage/ValueOutput;)V"))
    private void fission$save(AbstractChestBoat instance, ValueOutput output) {
        ContainerEntityPaper.addChestVehicleSaveData(instance, output);
    }

    @Redirect(method = "readAdditionalSaveData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractChestBoat;readChestVehicleSaveData(Lnet/minecraft/world/level/storage/ValueInput;)V"))
    private void fission$read(AbstractChestBoat instance, ValueInput input) {
        ContainerEntityPaper.readChestVehicleSaveData(instance, input);
    }

    @Redirect(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractChestBoat;interactWithContainerVehicle(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult fission$interact(AbstractChestBoat instance, Player player) {
        return ContainerEntityPaper.interactWithContainerVehicle(instance, player);
    }

    @Redirect(method = "unpackLootTable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractChestBoat;unpackChestVehicleLootTable(Lnet/minecraft/world/entity/player/Player;)V"))
    private void fission$unpack(AbstractChestBoat instance, @Nullable Player player) {
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

    @Inject(method = "openCustomInventoryScreen", at = @At("HEAD"), cancellable = true)
    private void fission$openScreen(Player player, CallbackInfo ci) {
        ci.cancel();
        // Paper - fix inventory open cancel - moved into below if
        if (player.level() instanceof ServerLevel level && player.openMenu((AbstractChestBoat) (Object) this).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation
            this.gameEvent(GameEvent.CONTAINER_OPEN, player);
            PiglinAi.angerNearbyPiglins(level, player, true);
        }
    }
}
