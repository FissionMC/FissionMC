package net.fissionmc.backend.mixin.world.entity;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Members Paper adds to {@link LivingEntity} (paper-server/patches/sources/net/minecraft/world/entity/LivingEntity.java.patch).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    @Shadow @Final protected static EntityDataAccessor<Byte> DATA_LIVING_ENTITY_FLAGS;
    @Shadow @Final protected static int LIVING_ENTITY_FLAG_IS_USING;
    @Shadow @Final protected static int LIVING_ENTITY_FLAG_OFF_HAND;
    @Shadow @Final protected static int DAMAGE_COOLDOWN_DURATION;
    @Shadow @Final private AttributeMap attributes;
    @Shadow @Final private Map<Holder<MobEffect>, MobEffectInstance> activeEffects;
    @Shadow protected int lastHurtByPlayerMemoryTime;
    @Shadow protected ItemStack useItem;
    @Shadow protected int useItemRemaining;
    @Shadow @Final protected EntityEquipment equipment;
    @Shadow protected Brain<?> brain;
    @Shadow protected it.unimi.dsi.fastutil.objects.Object2LongMap<Entity> recentKineticEnemies;

    @Shadow public abstract void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack stack);
    @Shadow public abstract boolean canBeAffected(MobEffectInstance newEffect);
    @Shadow protected abstract void onEffectAdded(MobEffectInstance effect, @Nullable Entity source);
    @Shadow protected abstract void onEffectUpdated(MobEffectInstance effect, boolean doRefreshAttributes, @Nullable Entity source);
    @Shadow protected abstract void onEffectsRemoved(java.util.Collection<MobEffectInstance> effects);
    @Shadow public abstract float getHealth();
    @Shadow public abstract void setHealth(float health);
    @Shadow public abstract ItemStack getItemBlockingWith();
    @Shadow protected abstract void blockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float damage, boolean fullyBlocked);
    @Shadow public abstract InteractionHand getUsedItemHand();
    @Shadow public abstract boolean isUsingItem();
    @Shadow public abstract float getYHeadRot();
    @Shadow public abstract boolean wasExperienceConsumed();
    @Shadow protected abstract boolean isAlwaysExperienceDropper();
    @Shadow public abstract boolean shouldDropExperience();
    @Shadow public abstract int getExperienceReward(ServerLevel level, @Nullable Entity killer);
    @Shadow public abstract double getAttributeValue(Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute);
    @Shadow public abstract int getArrowCount();
    @Shadow public abstract ItemStack getItemInHand(InteractionHand hand);
    @Shadow public abstract boolean swing(InteractionHand hand, SwingAnimation swingAnimation, boolean sendToSwingingEntity);
    @Shadow public abstract @Nullable ItemEntity createItemStackToDrop(ItemStack itemStack, boolean randomly, boolean thrownFromHand);
    @Shadow protected abstract void setLivingEntityFlag(int flag, boolean value);
    @Shadow private void updatePlayersWithNewEquipment(ServerLevel level, List<Pair<EquipmentSlot, ItemStack>> itemsToSend) {}
    @Shadow private void dismountVehicle(Entity vehicle) {}
    @Shadow protected abstract void triggerOnDeathMobEffects(ServerLevel level, Entity.RemovalReason reason);
    @Shadow public abstract net.minecraft.world.damagesource.CombatTracker getCombatTracker();

    protected LivingEntityMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    // CraftBukkit start
    private int expToDrop;
    private @Nullable List<net.fissionmc.craft.compat.DefaultDrop> deathDropItems = null;
    private org.bukkit.craftbukkit.attribute.CraftAttributeMap craftAttributes; // FissionMC: assigned at the end of the constructor
    private boolean collides = true; // TODO(behaviour): isPickable/isPushable check collides
    private Set<UUID> collidableExemptions = new java.util.HashSet<>();
    private boolean bukkitPickUpLoot;
    private boolean silentDeath = true; // Paper - mark entity as dying silently for cancellable death event
    private net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API // TODO(behaviour): shouldDiscardFriction, save/load
    private int invulnerableDuration = DAMAGE_COOLDOWN_DURATION; // Paper - configurable invulnerable duration // TODO(behaviour): hurtServer/handleDamageEvent use it
    // CraftBukkit end
    private List<Runnable> postDeathEventTasks = new java.util.ArrayList<>(); // Paper - after death event and death (no revive as converted entity in killedEntity) // TODO(behaviour): die()/death event flow
    private long lastJumpTime = 0L; // Paper - Prevent excessive velocity through repeated crits // TODO(behaviour): jumpFromGround
    // Paper start - lag compensate eating // TODO(behaviour): updateUsingItem/stopUsingItem/releaseUsingItem hunks
    private long eatStartTime;
    private int totalEatTimeTicks;
    // Paper end - lag compensate eating

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$initCraftAttributes(EntityType<? extends LivingEntity> type, Level level, CallbackInfo ci) {
        this.craftAttributes = new org.bukkit.craftbukkit.attribute.CraftAttributeMap(this.attributes); // CraftBukkit
    }

    // FissionMC: `silent` argument of the in-progress onEquipItem(slot, old, new, silent) call
    @Unique private boolean fission$silentEquip = false;

    // TODO(behaviour): vanilla onEquipItem must skip the equip sound when fission$silentEquip is set (CraftBukkit `&& !silent`)
    public void onEquipItem(final EquipmentSlot slot, final ItemStack oldStack, final ItemStack stack, final boolean silent) {
        final boolean prev = this.fission$silentEquip;
        this.fission$silentEquip = silent;
        try {
            this.onEquipItem(slot, oldStack, stack);
        } finally {
            this.fission$silentEquip = prev;
        }
    }

    @Override
    public void remove(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause eventCause) { // CraftBukkit - add Bukkit remove cause
        if ((reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED) && this.level() instanceof ServerLevel level) {
            this.triggerOnDeathMobEffects(level, reason);
        }

        super.remove(reason, eventCause); // CraftBukkit
        this.brain.clearMemories();
        this.getCombatTracker().recheckStatus();
    }

    // TODO(behaviour): vanilla drop(stack, thrownFromHand, prediction) should delegate here (PlayerDropItemEvent)
    public @Nullable ItemEntity drop(final ItemStack itemStack, final boolean thrownFromHand, final Prediction prediction, final boolean callEvent, final java.util.function.@Nullable Consumer<org.bukkit.entity.Item> entityOperation) {
        return this.drop(itemStack, thrownFromHand, prediction, false, callEvent, entityOperation);
    }

    public @Nullable ItemEntity drop(final ItemStack itemStack, final boolean thrownFromHand, final Prediction prediction, final boolean randomly, final boolean callEvent, final java.util.function.@Nullable Consumer<org.bukkit.entity.Item> entityOperation) {
        // Paper end - Extend dropItem API
        if (itemStack.isEmpty()) {
            return null;
        }

        this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, prediction != Prediction.PREDICTED);
        if (this.level().isClientSide()) {
            return null;
        }

        ItemEntity entity = this.createItemStackToDrop(itemStack, randomly, thrownFromHand); // Paper - Extend dropItem API (allow change randomly)
        if (entity != null) {
            // CraftBukkit start - fire PlayerDropItemEvent
            if (entityOperation != null) entityOperation.accept((org.bukkit.entity.Item) entity.getBukkitEntity());
            if (callEvent && this.getBukkitEntity() instanceof org.bukkit.entity.Player player) {
                org.bukkit.entity.Item drop = (org.bukkit.entity.Item) entity.getBukkitEntity();

                org.bukkit.event.player.PlayerDropItemEvent event = new org.bukkit.event.player.PlayerDropItemEvent(player, drop);
                this.level().getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    org.bukkit.inventory.ItemStack inHandItem = player.getInventory().getItemInMainHand();
                    if (thrownFromHand && inHandItem.getAmount() == 0) {
                        // The complete stack was dropped
                        player.getInventory().setItemInMainHand(drop.getItemStack());
                    } else if (thrownFromHand && inHandItem.isSimilar(drop.getItemStack()) && inHandItem.getAmount() < inHandItem.getMaxStackSize() && drop.getItemStack().getAmount() == 1) {
                        // Only one item is dropped
                        inHandItem.setAmount(inHandItem.getAmount() + 1);
                        player.getInventory().setItemInMainHand(inHandItem);
                    } else {
                        // Fallback
                        player.getInventory().addItem(drop.getItemStack());
                    }
                    return null;
                }
            }
            // CraftBukkit end
            this.level().addFreshEntity(entity);
        }

        return entity;
    }

    private boolean tickingEffects = false; // CraftBukkit // TODO(behaviour): set while tickEffects iterates; deferred updates run after
    private final java.util.Queue<Runnable> deferredEffectUpdates = new java.util.concurrent.ConcurrentLinkedQueue<>(); // CraftBukkit

    // TODO(behaviour): vanilla removeAllEffects() should delegate here with Cause.UNKNOWN
    public boolean removeAllEffects(EntityPotionEffectEvent.Cause cause) {
        // CraftBukkit end
        if (this.level().isClientSide()) {
            return false;
        }

        if (this.activeEffects.isEmpty()) {
            return false;
        }

        // CraftBukkit start
        List<MobEffectInstance> toRemove = new java.util.LinkedList<>();
        Iterator<MobEffectInstance> iterator = this.activeEffects.values().iterator();
        while (iterator.hasNext()) {
            MobEffectInstance effect = iterator.next();
            EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent((LivingEntity) (Object) this, effect, null, cause, EntityPotionEffectEvent.Action.CLEARED);
            if (event.isCancelled()) {
                continue;
            }

            iterator.remove();
            toRemove.add(effect);
        }

        this.onEffectsRemoved(toRemove);
        return !toRemove.isEmpty();
        // CraftBukkit end
    }

    // CraftBukkit start
    public boolean addEffect(final MobEffectInstance newEffect, final EntityPotionEffectEvent.Cause cause) {
        return this.addEffect(newEffect, (Entity) null, cause);
    }

    // TODO(behaviour): vanilla addEffect(effect, source) should delegate here with Cause.UNKNOWN
    public boolean addEffect(final MobEffectInstance newEffect, final @Nullable Entity source, final EntityPotionEffectEvent.Cause cause) {
        // Paper start - Don't fire sync event during generation
        return this.addEffect(newEffect, source, cause, true);
    }

    public boolean addEffect(final MobEffectInstance newEffect, final @Nullable Entity source, final EntityPotionEffectEvent.Cause cause, final boolean fireEvent) {
        // Paper end - Don't fire sync event during generation
        if (this.tickingEffects) {
            this.deferredEffectUpdates.offer(() -> this.addEffect(newEffect, source, cause, fireEvent));
            return true;
        }
        // CraftBukkit end
        if (!this.canBeAffected(newEffect)) {
            return false;
        }

        MobEffectInstance effect = this.activeEffects.get(newEffect.getEffect());
        boolean changed = false;
        // Paper start
        boolean override = false;
        boolean addAsHiddenEffect = false;
        if (effect != null) {
            override = new MobEffectInstance(effect).update(newEffect);
            addAsHiddenEffect = effect.getAmplifier() > newEffect.getAmplifier() && effect.isShorterDurationThan(newEffect);
        }

        if (fireEvent) {
            EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent((LivingEntity) (Object) this, effect, newEffect, source, cause, null, override);
            override = event.isOverride();
            if (event.isCancelled()) {
                return false;
            }
        }
        // Paper end
        if (effect == null) {
            this.activeEffects.put(newEffect.getEffect(), newEffect);
            this.onEffectAdded(newEffect, source);
            changed = true;
            newEffect.onEffectAdded((LivingEntity) (Object) this);
        } else if (override) { // Paper - Don't fire sync event during generation
            effect.update(newEffect); // Paper
            this.onEffectUpdated(effect, true, source);
            changed = true;
            // Paper start - Properly update hidden effects
        } else if (addAsHiddenEffect) {
            if (effect.hiddenEffect == null) {
                effect.hiddenEffect = new MobEffectInstance(newEffect);
            } else {
                effect.hiddenEffect.update(newEffect);
            }
            // Paper end - Properly update hidden effects
        }

        newEffect.onEffectStarted((LivingEntity) (Object) this);
        return changed;
    }

    // TODO(behaviour): vanilla removeEffectNoUpdate(effect)/removeEffect(effect) should delegate here with Cause.UNKNOWN
    public final @Nullable MobEffectInstance removeEffectNoUpdate(final Holder<MobEffect> effect, final EntityPotionEffectEvent.Cause cause) {
        if (this.tickingEffects) {
            this.deferredEffectUpdates.offer(() -> this.removeEffectNoUpdate(effect, cause));
            return null;
        }

        MobEffectInstance effectInstance = this.activeEffects.get(effect);
        if (effectInstance == null) {
            return null;
        }

        EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent((LivingEntity) (Object) this, effectInstance, null, cause);
        if (event.isCancelled()) {
            return null;
        }
        // CraftBukkit end
        return this.activeEffects.remove(effect);
    }

    public boolean removeEffect(final Holder<MobEffect> effect, final EntityPotionEffectEvent.Cause cause) {
        if (this.tickingEffects) {
            this.deferredEffectUpdates.offer(() -> this.removeEffect(effect, cause));
            return true;
        }

        MobEffectInstance effectInstance = this.removeEffectNoUpdate(effect, cause);
        // CraftBukkit end
        if (effectInstance != null) {
            this.onEffectsRemoved(List.of(effectInstance));
            return true;
        }

        return false;
    }

    // TODO(behaviour): vanilla heal(float) should delegate here with RegainReason.CUSTOM
    public void heal(final float heal, final EntityRegainHealthEvent.RegainReason regainReason) {
        // Paper start - Forward
        this.heal(heal, regainReason, false);
    }

    public void heal(final float heal, final EntityRegainHealthEvent.RegainReason regainReason, final boolean isFastRegen) {
        // Paper end - Forward
        float health = this.getHealth();
        if (health > 0.0F) {
            EntityRegainHealthEvent event = new EntityRegainHealthEvent(this.getBukkitEntity(), heal, regainReason, isFastRegen); // Paper
            // Suppress during worldgen
            if (this.valid) {
                this.level().getCraftServer().getPluginManager().callEvent(event);
            }

            if (!event.isCancelled()) {
                this.setHealth((float) (this.getHealth() + event.getAmount()));
            }
            // CraftBukkit end
        }
    }

    // FissionMC: dryRun == false is vanilla applyItemBlocking; the dry run is Paper's body without the item/attacker side effects
    public float applyItemBlocking(final ServerLevel level, final DamageSource source, final float damage, final boolean dryRun) {
        if (!dryRun) {
            return ((LivingEntity) (Object) this).applyItemBlocking(level, source, damage);
        }
        return this.canBlockAttack(source, damage) ? this.resolveBlockedDamage(source, damage) : 0.0F;
    }

    // Paper start - copied from above split by relevant part
    public boolean canBlockAttack(DamageSource source, float damage) {
        if (damage <= 0.0F) {
            return false;
        }

        ItemStack blockingWith = this.getItemBlockingWith();
        if (blockingWith == null) {
            return false;
        }

        BlocksAttacks blocksAttacks = blockingWith.get(DataComponents.BLOCKS_ATTACKS);
        if (blocksAttacks != null && !blocksAttacks.bypassedBy().map(t -> t.contains(source.typeHolder())).orElse(false)) {
            if (source.getDirectEntity() instanceof AbstractArrow abstractArrow && abstractArrow.getPierceLevel() > 0) {
                return false;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    public float resolveBlockedDamage(DamageSource source, float damage) {
        Vec3 sourcePosition = source.getSourcePosition();
        double angle;
        if (sourcePosition != null) {
            Vec3 viewVector = Entity.calculateViewVector(0.0F, this.getYHeadRot());
            Vec3 vectorTo = sourcePosition.subtract(this.position());
            vectorTo = new Vec3(vectorTo.x, 0.0, vectorTo.z).normalize();
            angle = Math.acos(vectorTo.dot(viewVector));
        } else {
            angle = (float) Math.PI;
        }

        BlocksAttacks blocksAttacks = this.getItemBlockingWith().get(DataComponents.BLOCKS_ATTACKS);
        return blocksAttacks.resolveBlockedDamage(source, damage, angle);
    }

    public void blockingItemEffects(ServerLevel level, DamageSource source, final float damage, float damageBlocked) {
        ItemStack blockingWith = this.getItemBlockingWith();
        if (blockingWith == null) return;

        BlocksAttacks blocksAttacks = blockingWith.get(DataComponents.BLOCKS_ATTACKS);
        if (blocksAttacks == null) return;

        blocksAttacks.hurtBlockingItem(this.level(), blockingWith, (LivingEntity) (Object) this, this.getUsedItemHand(), damageBlocked);
        if (damageBlocked > 0.0F && !source.is(DamageTypeTags.IS_PROJECTILE) && source.getDirectEntity() instanceof LivingEntity livingEntity && livingEntity.distanceToSqr(this) <= Mth.square(200.0)) { // Paper - Fix shield disable inconsistency & Check distance in entity interactions
            this.blockUsingItem(level, livingEntity, source, damage, damageBlocked >= damage);
        }
    }
    // Paper end - copied from above split by relevant part

    // TODO(behaviour): vanilla dropExperience should award expToDrop (CraftBukkit - set from the death event)
    public int getExpReward(final ServerLevel level, final @Nullable Entity killer) { // CraftBukkit
        if (!this.wasExperienceConsumed()
            && (
                this.isAlwaysExperienceDropper()
                    || this.lastHurtByPlayerMemoryTime > 0 && this.shouldDropExperience() && level.getGameRules().get(GameRules.MOB_DROPS)
            )) {
            return this.getExperienceReward(level, killer); // CraftBukkit
        }
        return 0; // CraftBukkit
    }

    // TODO(behaviour): vanilla knockback overloads (and their callers) should route through the event overloads
    public void knockback(double power, double xd, double zd, final DamageSource source, final float damage, final boolean comesFromEffect, @Nullable Entity attacker, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause eventCause) { // Paper - knockback events
        power *= 1.0 - this.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        if (true || !(power <= 0.0)) { // CraftBukkit - Call event even when force is 0
            // this.needsSync = true; // Paper - moved down
            Vec3 deltaMovement = this.getDeltaMovement();

            while (xd * xd + zd * zd < 1.0E-5F) {
                xd = (this.random.nextDouble() - this.random.nextDouble()) * 0.01;
                zd = (this.random.nextDouble() - this.random.nextDouble()) * 0.01;
            }

            Vec3 deltaVector = new Vec3(xd, 0.0, zd).normalize().scale(power);
            Vec3 targetMovement = new Vec3( // Paper - knockback events
                deltaMovement.x / 2.0 - deltaVector.x,
                this.onGround() ? Math.min(0.4, deltaMovement.y / 2.0 + power) : deltaMovement.y,
                deltaMovement.z / 2.0 - deltaVector.z
            );
            // Paper start - knockback events
            Vec3 knockback = targetMovement.subtract(deltaMovement);
            io.papermc.paper.event.entity.EntityKnockbackEvent event = CraftEventFactory.callEntityKnockbackEvent(this.getBukkitEntity(), attacker, attacker, eventCause, power, knockback);
            if (event.isCancelled()) {
                return;
            }

            this.needsSync = true;
            this.setDeltaMovement(deltaMovement.add(event.getKnockback().getX(), event.getKnockback().getY(), event.getKnockback().getZ()));
            // Paper end - knockback events
        }
    }

    public void knockback(double power, double xd, double zd, final DamageSource source, final float damage, @Nullable Entity attacker, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause eventCause) {
        this.knockback(power, xd, zd, source, damage, false, attacker, eventCause);
        // Paper end - knockback events
    }

    // TODO(behaviour): vanilla setArrowCount(count) should delegate here with reset = false
    public final void setArrowCount(final int count, final boolean reset) {
        org.bukkit.event.entity.ArrowBodyCountChangeEvent event = CraftEventFactory.callArrowBodyCountChangeEvent((LivingEntity) (Object) this, this.getArrowCount(), count, reset);
        if (event.isCancelled()) {
            return;
        }
        this.entityData.set(LivingEntity.DATA_ARROW_COUNT_ID, event.getNewAmount());
        // CraftBukkit end
    }

    public void setItemSlot(final EquipmentSlot slot, final ItemStack itemStack, final boolean silent) {
        this.onEquipItem(slot, this.equipment.set(slot, itemStack), itemStack, silent);
        // Paper end
    }

    // TODO(behaviour): vanilla onAttack() should delegate here with null; subclasses override onAttack(Entity)
    public void onAttack(Entity entity) {
        // Paper end
    }

    // TODO(behaviour): vanilla stopRiding() should delegate here with false
    @Override
    public void stopRiding(final boolean suppressCancellation) { // Paper - Force entity dismount during teleportation
        Entity oldVehicle = this.getVehicle();
        super.stopRiding(suppressCancellation); // Paper - Force entity dismount during teleportation
        if (oldVehicle != null && oldVehicle != this.getVehicle() && !this.level().isClientSide() && oldVehicle.valid) { // Paper - don't process on world gen
            this.dismountVehicle(oldVehicle);
        }
    }

    @Override
    public boolean isCollidable(boolean ignoreClimbing) {
        // Paper end - Climbing should not bypass cramming gamerule
        return (this.isAlwaysTicking() || !(this.level() instanceof ServerLevel level && !level.isPositionEntityTicking(this.blockPosition())))
            && this.isAlive()
            && !this.isSpectator()
            && this.collides // Paper - setCollidable API
            && (ignoreClimbing || !((LivingEntity) (Object) this).onClimbable()); // Paper - Climbing should not bypass cramming gamerule
    }

    // CraftBukkit start - collidable API
    @Override
    public boolean canCollideWithBukkit(Entity entity) {
        return this.isPushable() && this.collides != this.collidableExemptions.contains(entity.getUUID());
    }
    // CraftBukkit end

    // Paper start - Properly cancel usable items
    public void resyncUsingItem(ServerPlayer serverPlayer) {
        this.resendPossiblyDesyncedDataValues(java.util.List.of(DATA_LIVING_ENTITY_FLAGS), serverPlayer);
    }
    // Paper end - Properly cancel usable items

    // TODO(behaviour): vanilla startUsingItem(hand) should delegate here with false
    public void startUsingItem(final InteractionHand hand, final boolean forceUpdate) {
        // Paper end - Prevent consuming the wrong itemstack
        ItemStack itemStack = this.getItemInHand(hand);
        if ((!itemStack.isEmpty() && !this.isUsingItem()) || forceUpdate) { // Paper - Prevent consuming the wrong itemstack
            this.useItem = itemStack;
            // Paper start - lag compensate eating
            this.useItemRemaining = this.totalEatTimeTicks = itemStack.getUseDuration((LivingEntity) (Object) this);
            this.eatStartTime = System.nanoTime();
            // Paper end - lag compensate eating
            if (!this.level().isClientSide()) {
                this.updatePlayersWithNewEquipment((ServerLevel) this.level(), List.of(Pair.of(hand.asEquipmentSlot(), this.useItem)));
                this.setLivingEntityFlag(LIVING_ENTITY_FLAG_IS_USING, true);
                this.setLivingEntityFlag(LIVING_ENTITY_FLAG_OFF_HAND, hand == InteractionHand.OFF_HAND);
                this.useItem.causeUseVibration(this, GameEvent.ITEM_INTERACT_START);
                if (this.useItem.has(DataComponents.KINETIC_WEAPON)) {
                    this.recentKineticEnemies = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
                }
            }
        }
    }

    // CraftBukkit start
    @Override
    public float getBukkitYaw() {
        return this.getYHeadRot();
    }
    // CraftBukkit end

    // Paper start
    public HitResult getRayTrace(int maxDistance, ClipContext.Fluid fluidCollisionOption) {
        if (maxDistance < 1 || maxDistance > 120) {
            throw new IllegalArgumentException("maxDistance must be between 1-120");
        }

        Vec3 start = new Vec3(getX(), getY() + getEyeHeight(), getZ());
        org.bukkit.util.Vector dir = getBukkitEntity().getLocation().getDirection().multiply(maxDistance);
        Vec3 end = new Vec3(start.x + dir.getX(), start.y + dir.getY(), start.z + dir.getZ());
        ClipContext raytrace = new ClipContext(start, end, ClipContext.Block.OUTLINE, fluidCollisionOption, this);

        return this.level().clip(raytrace);
    }

    public net.minecraft.world.phys.@Nullable EntityHitResult getTargetEntity(int maxDistance) {
        if (maxDistance < 1 || maxDistance > 120) {
            throw new IllegalArgumentException("maxDistance must be between 1-120");
        }

        Vec3 start = this.getEyePosition(1.0F);
        Vec3 direction = this.getLookAngle();
        Vec3 end = start.add(direction.x * maxDistance, direction.y * maxDistance, direction.z * maxDistance);

        List<Entity> entityList = this.level().getEntities(this, getBoundingBox().expandTowards(direction.x * maxDistance, direction.y * maxDistance, direction.z * maxDistance).inflate(1.0), EntitySelector.NO_SPECTATORS.and(Entity::isPickable));

        double distance = 0.0;
        net.minecraft.world.phys.EntityHitResult result = null;

        for (Entity entity : entityList) {
            final double inflationAmount = entity.getPickRadius();
            AABB aabb = entity.getBoundingBox().inflate(inflationAmount, inflationAmount, inflationAmount);
            Optional<Vec3> rayTraceResult = aabb.clip(start, end);

            if (rayTraceResult.isPresent()) {
                Vec3 rayTrace = rayTraceResult.get();
                double distanceTo = start.distanceToSqr(rayTrace);
                if (distanceTo < distance || distance == 0.0) {
                    result = new net.minecraft.world.phys.EntityHitResult(entity, rayTrace);
                    distance = distanceTo;
                }
            }
        }

        return result;
    }
    // Paper end

    // Paper start
    @Override
    public org.bukkit.craftbukkit.entity.CraftLivingEntity getBukkitEntity() {
        return (org.bukkit.craftbukkit.entity.CraftLivingEntity) super.getBukkitEntity();
    }
    // Paper end
}
