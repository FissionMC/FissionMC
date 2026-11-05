package net.fissionmc.backend.mixin.world.entity.decoration;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fissionmc.craft.compat.DefaultDrop;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/entity/decoration/ArmorStand.java.patch
 * Done: canTick/canMove (tick, move), PlayerArmorStandManipulateEvent, damage events (EntityDamageEvent family; one call at the head classifying
 * the branches), EntityDeathEvent via brokenByAnything/brokenByPlayer/kill(source), invisible stands can be damaged.
 * TODO(behaviour): save/load "Paper.CanTickOverride", setItemSlot(silent) noTickEquipmentDirty, shouldTickDeath, config: doCollisionEntityLookups.
 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin extends LivingEntity {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private boolean canMove = true; // Paper
    // Paper start - Allow ArmorStands not to tick
    private boolean canTick = true;
    private boolean canTickSetByAPI = false;
    private boolean noTickEquipmentDirty = false;
    // Paper end - Allow ArmorStands not to tick

    @Shadow private boolean invisible;
    @Shadow protected abstract void playBrokenSound();
    @Shadow public abstract boolean isMarker();

    private org.bukkit.event.entity.EntityDeathEvent fission$lastDeathEvent;

    private ArmorStandMixin() {
        super(null, null);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", at = @At("RETURN"))
    private void fission$init(final EntityType<? extends ArmorStand> type, final Level level, final CallbackInfo ci) {
        if (level != null) this.canTick = level.paperConfig().entities.armorStands.tick; // Paper - Allow ArmorStands not to tick
    }

    @Override
    public float getBukkitYaw() {
        return this.getYRot();
    }

    @Override
    public boolean isCollidable(boolean ignoreClimbing) { // Paper - Climbing should not bypass cramming gamerule
        return false;
    }

    @Override
    public void move(MoverType type, Vec3 movement) {
        if (this.canMove) {
            super.move(type, movement);
        }
    }

    // TODO(behaviour): ArmorStand does not declare tick() (inherited from LivingEntity), so it cannot be injected here; hook
    // LivingEntity#tick HEAD (instanceof ArmorStand) and call this gate. Paper: ArmorStand.java.patch "canTick" hunk.
    private void fission$tick(final CallbackInfo ci) {
        if (!this.canTick) {
            if (this.noTickEquipmentDirty) {
                this.noTickEquipmentDirty = false;
                this.detectEquipmentUpdates();
            }
            if (this.isDeadOrDying()) {
                this.tickDeath();
            }
            ci.cancel();
        }
    }

    // PlayerArmorStandManipulateEvent
    @Inject(method = "swapItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z", ordinal = 0), cancellable = true)
    private void fission$manipulate(final Player player, final EquipmentSlot slot, final ItemStack playerItemStack, final InteractionHand hand, final CallbackInfoReturnable<Boolean> cir,
                                    @Local(name = "itemStack") ItemStack itemStack) {
        org.bukkit.inventory.ItemStack armorStandItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemStack);
        org.bukkit.inventory.ItemStack playerHeldItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(playerItemStack);
        org.bukkit.entity.Player player1 = (org.bukkit.entity.Player) player.getBukkitEntity();
        org.bukkit.entity.ArmorStand self = (org.bukkit.entity.ArmorStand) this.getBukkitEntity();
        org.bukkit.inventory.EquipmentSlot slot1 = org.bukkit.craftbukkit.CraftEquipmentSlot.getSlot(slot);
        org.bukkit.inventory.EquipmentSlot hand1 = org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand);
        org.bukkit.event.player.PlayerArmorStandManipulateEvent armorStandManipulateEvent = new org.bukkit.event.player.PlayerArmorStandManipulateEvent(player1, self, playerHeldItem, armorStandItem, slot1, hand1);
        this.level().getCraftServer().getPluginManager().callEvent(armorStandManipulateEvent);
        if (armorStandManipulateEvent.isCancelled()) {
            cir.setReturnValue(true);
        }
    }

    // Damage events: Paper calls handleNonLivingEntityDamageEvent in each effective branch of hurtServer; the same classification is done here once.
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void fission$damageEvents(final ServerLevel level, final DamageSource source, final float damage, final CallbackInfoReturnable<Boolean> cir) {
        if (this.isRemoved()) return;
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING) && source.getEntity() instanceof Mob) return;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage)) {
                cir.setReturnValue(false);
            }
            return;
        }
        if (this.isInvulnerableTo(level, source) /*|| this.invisible*/ || this.isMarker()) return; // CraftBukkit
        if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage, true, this.invisible)) {
            cir.setReturnValue(false);
        }
    }

    // CraftBukkit: invisible armor stands can be damaged (the `|| this.invisible` check was commented out)
    @ModifyExpressionValue(method = "hurtServer", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/decoration/ArmorStand;invisible:Z"))
    private boolean fission$ignoreInvisible(final boolean invisible) {
        return false;
    }

    // this.kill(level, source.getEntity()) -> this.kill(level, source[, false when a death event was already called])
    @WrapOperation(method = {"hurtServer", "causeDamage"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/ArmorStand;kill(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)V"))
    private void fission$kill(final ArmorStand self, final ServerLevel level, final Entity attributedTo, final Operation<Void> original, @Local(argsOnly = true) DamageSource source) {
        org.bukkit.event.entity.EntityDeathEvent event = this.fission$lastDeathEvent;
        this.fission$lastDeathEvent = null;
        if (event == null) {
            self.kill(level, source, true);
        } else if (!event.isCancelled()) {
            self.kill(level, source, false); // CraftBukkit - we still need to kill to follow vanilla logic (emit the game event etc...)
        }
    }

    // brokenByPlayer: vanilla body replaced
    @Inject(method = "brokenByPlayer", at = @At("HEAD"), cancellable = true)
    private void fission$brokenByPlayer(final ServerLevel level, final DamageSource source, final CallbackInfo ci) {
        ItemStack result = new ItemStack(Items.ARMOR_STAND);
        result.set(DataComponents.CUSTOM_NAME, this.getCustomName());
        this.deathDropItems = new java.util.ArrayList<>(); // Paper
        this.deathDropItems.add(new DefaultDrop(result, stack -> Block.popResource(this.level(), this.blockPosition(), stack))); // CraftBukkit - add to drops // Paper - Restore vanilla drops behavior
        this.fission$lastDeathEvent = this.fission$brokenByAnything(level, source); // Paper
        ci.cancel();
    }

    @Inject(method = "brokenByAnything", at = @At("HEAD"), cancellable = true)
    private void fission$brokenByAnythingHook(final ServerLevel level, final DamageSource source, final CallbackInfo ci) {
        this.fission$lastDeathEvent = this.fission$brokenByAnything(level, source);
        ci.cancel();
    }

    private org.bukkit.event.entity.EntityDeathEvent fission$brokenByAnything(final ServerLevel level, final DamageSource source) {
        this.playBrokenSound();
        if (this.deathDropItems == null) this.deathDropItems = new java.util.ArrayList<>(); // Paper
        this.dropAllDeathLoot(level, source);

        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            this.postDeathEventTasks.add(() -> this.equipment.set(slot, ItemStack.EMPTY)); // Paper - move equipment removal past event call
            ItemStack itemStack = this.equipment.get(slot); // Paper
            if (!itemStack.isEmpty() && !EnchantmentHelper.has(itemStack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                this.deathDropItems.add(new DefaultDrop(itemStack, stack -> Block.popResource(this.level(), this.blockPosition().above(), stack))); // CraftBukkit - add to drops // Paper - Restore vanilla drops behavior
            }
        }
        org.bukkit.event.entity.EntityDeathEvent deathEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityDeathEvent(level, this, source, this.deathDropItems, true);
        this.deathDropItems = null;
        if (!deathEvent.isCancelled()) {
            this.postDeathEventTasks.forEach(Runnable::run);
        }
        this.postDeathEventTasks.clear();
        return deathEvent;
    }

    public void kill(final ServerLevel level, final DamageSource source) {
        this.kill(level, source, true);
    }

    public void kill(final ServerLevel level, final DamageSource source, final boolean callEvent) {
        if (callEvent && !org.bukkit.craftbukkit.event.CraftEventFactory.callEntityDeathEvent(level, this, (source == null ? this.damageSources().genericKill() : source))) {
            return;
        }
        final Entity attributedTo = source == null ? null : source.getEntity();
        this.remove(Entity.RemovalReason.KILLED);
        this.gameEvent(GameEvent.ENTITY_DIE, attributedTo != null ? attributedTo : this);
    }
}
