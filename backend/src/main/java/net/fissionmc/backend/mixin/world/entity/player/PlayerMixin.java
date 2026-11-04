package net.fissionmc.backend.mixin.world.entity.player;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Unit;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/player/Player.java.patch */
@Mixin(Player.class)
public abstract class PlayerMixin extends Avatar {
    @Shadow @Final public InventoryMenu inventoryMenu;
    @Shadow public AbstractContainerMenu containerMenu;
    @Shadow protected FoodData foodData;
    @Shadow @Final private Abilities abilities;

    @Shadow protected abstract void closeContainer();
    @Shadow protected abstract void doCloseContainer();
    @Shadow public abstract boolean hasContainerOpen();
    @Shadow public abstract Either<Player.BedSleepingProblem, Unit> startSleepInBed(AbstractBedBlock bedBlock, BlockState bedBlockState, BedRule rule, BlockPos pos);

    protected PlayerMixin(final EntityType<? extends LivingEntity> type, final Level level) {
        super(type, level);
    }

    // Surface: public fields (see surface/members/player.txt). Mixin requires private fields; the loader widens them.
    private boolean affectsSpawning = true; // Paper - Affects Spawning API
    private net.kyori.adventure.util.TriState flyingFallDamage = net.kyori.adventure.util.TriState.NOT_SET; // Paper - flying fall damage
    // TODO(behaviour): causeFallDamage checks flyingFallDamage; isImmobile also checks isRemoved()/!valid

    // CraftBukkit start
    private boolean fauxSleeping;
    private int oldLevel = -1;

    @Override
    public org.bukkit.craftbukkit.entity.CraftHumanEntity getBukkitEntity() {
        return (org.bukkit.craftbukkit.entity.CraftHumanEntity) super.getBukkitEntity();
    }
    // CraftBukkit end

    // Paper start - Inventory close reason; unused code, but to keep signatures aligned
    public void closeContainer(org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        this.closeContainer();
        this.containerMenu = this.inventoryMenu;
    }
    // Paper end - Inventory close reason
    // Paper start - special close for unloaded inventory
    public void closeUnloadedInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        this.containerMenu = this.inventoryMenu;
    }
    // Paper end - special close for unloaded inventory

    public Either<Player.BedSleepingProblem, Unit> startSleepInBed(
        final AbstractBedBlock bedBlock, final BlockState bedBlockState, final BedRule rule, final BlockPos pos, final boolean force // Paper - add force
    ) {
        // TODO(behaviour): Paper moves the vanilla body here and makes the 4-arg overload delegate with force=false;
        // ServerPlayer's override consumes `force` (bypass bed checks) and fires PlayerBedEnterEvent.
        return this.startSleepInBed(bedBlock, bedBlockState, rule, pos);
    }

    public void causeFoodExhaustion(final float amount, final org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason reason) {
        // CraftBukkit end
        if (!this.abilities.invulnerable) {
            if (!this.level().isClientSide()) {
                // CraftBukkit start
                org.bukkit.event.entity.EntityExhaustionEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerExhaustionEvent((Player) (Object) this, reason, amount);
                if (!event.isCancelled()) {
                    this.foodData.addExhaustion(event.getExhaustion());
                }
                // CraftBukkit end
            }
        }
    }
    // TODO(behaviour): vanilla causeFoodExhaustion(float) should delegate with ExhaustionReason.UNKNOWN

    // CraftBukkit start - add Bukkit remove cause
    // TODO(behaviour): vanilla remove(RemovalReason) should route through this overload (see EntityMixin)
    @Override
    public void remove(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause eventCause) { // CraftBukkit - add Bukkit remove cause
        super.remove(reason, eventCause); // CraftBukkit - add Bukkit remove cause
        this.inventoryMenu.removed((Player) (Object) this);
        if (this.hasContainerOpen()) {
            this.doCloseContainer();
        }
    }
    // CraftBukkit end

    // Paper start - Force entity dismount during teleportation
    // TODO(behaviour): vanilla removeVehicle() should route through this overload (see EntityMixin)
    @Override
    public void removeVehicle(boolean suppressCancellation) {
        super.removeVehicle(suppressCancellation);
        // Paper end - Force entity dismount during teleportation
        this.boardingCooldown = 0;
    }
}
