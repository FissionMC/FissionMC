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
public abstract class LivingEntityMixin extends Entity implements net.fissionmc.backend.world.entity.LivingEntityDuck {
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
    @Shadow protected abstract void completeUsingItem();
    @Shadow public abstract int getTicksUsingItem();
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

    // FissionMC: no override of remove(reason, cause): Entity#remove(reason, cause) runs vanilla remove(reason), which LivingEntity overrides
    // with the same body (triggerOnDeathMobEffects, brain/combat tracker reset)

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

    // FissionMC: attacker/cause travel through vanilla knockback(power, xd, zd, source, damage, comesFromEffect) (subclasses override it),
    // whose body is replaced by fission$paperKnockback
    @Unique private @Nullable Entity fission$knockbackAttacker;
    @Unique private io.papermc.paper.event.entity.EntityKnockbackEvent.@Nullable Cause fission$knockbackCause;

    public void knockback(double power, double xd, double zd, final DamageSource source, final float damage, final boolean comesFromEffect, @Nullable Entity attacker, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause eventCause) { // Paper - knockback events
        final Entity prevAttacker = this.fission$knockbackAttacker;
        final io.papermc.paper.event.entity.EntityKnockbackEvent.Cause prevCause = this.fission$knockbackCause;
        this.fission$knockbackAttacker = attacker;
        this.fission$knockbackCause = eventCause;
        try {
            ((LivingEntity) (Object) this).knockback(power, xd, zd, source, damage, comesFromEffect);
        } finally {
            this.fission$knockbackAttacker = prevAttacker;
            this.fission$knockbackCause = prevCause;
        }
    }

    @Inject(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at = @At("HEAD"), cancellable = true)
    private void fission$knockbackEvent(double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect, CallbackInfo ci) {
        ci.cancel();
        final io.papermc.paper.event.entity.EntityKnockbackEvent.Cause cause = this.fission$knockbackCause != null ? this.fission$knockbackCause : io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.UNKNOWN;
        final Entity attacker = this.fission$knockbackAttacker;
        this.fission$knockbackAttacker = null;
        this.fission$knockbackCause = null;
        this.fission$paperKnockback(power, xd, zd, source, damage, comesFromEffect, attacker, cause);
    }

    @Unique
    private void fission$paperKnockback(double power, double xd, double zd, final DamageSource source, final float damage, final boolean comesFromEffect, @Nullable Entity attacker, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause eventCause) { // Paper - knockback events
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

    // FissionMC: no override of stopRiding(suppressCancellation): Entity#stopRiding(boolean) runs vanilla stopRiding(), which LivingEntity
    // overrides; the oldVehicle.valid check is an injection (fission$dismountOnlyValid)

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

    // =====================================================================================================
    // Wave 2: behaviour hunks of LivingEntity.java.patch
    // =====================================================================================================

    @Shadow @Final private static EntityDataAccessor<Float> DATA_HEALTH_ID;
    @Shadow private boolean discardFriction;
    @Shadow public int hurtTime;
    @Shadow public int hurtDuration;
    @Shadow public int damageCooldownTime;
    @Shadow protected boolean dead;
    @Shadow protected int noActionTime;
    @Shadow protected float lastHurt;
    @Shadow private @Nullable DamageSource lastDamageSource;
    @Shadow private long lastDamageStamp;
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isDeadOrDying();
    @Shadow public abstract ItemStack getUseItem();
    @Shadow public abstract boolean isSleeping();
    @Shadow public abstract void stopSleeping();
    @Shadow public abstract boolean hasEffect(Holder<MobEffect> effect);
    @Shadow public abstract @Nullable MobEffectInstance getEffect(Holder<MobEffect> effect);
    @Shadow public abstract ItemStack getItemBySlot(EquipmentSlot slot);
    @Shadow protected abstract void resolveMobResponsibleForDamage(DamageSource source);
    @Shadow protected abstract net.minecraft.world.entity.player.@Nullable Player resolvePlayerResponsibleForDamage(DamageSource source);
    @Shadow private boolean checkTotemDeathProtection(DamageSource killingDamage) { return false; }
    @Shadow protected abstract void playHurtSound(DamageSource source);
    @Shadow private void playSecondaryHurtSound(DamageSource source) {}
    @Shadow public abstract void die(DamageSource source);
    @Shadow public abstract void dealDefaultKnockback(DamageSource source, float damage, boolean blocked);
    @Shadow public abstract java.util.Collection<MobEffectInstance> getActiveEffects();
    @Shadow protected abstract void hurtHelmet(DamageSource source, float damage);
    @Shadow protected abstract void hurtArmor(DamageSource source, float damage);
    @Shadow protected abstract float getDamageAfterArmorAbsorb(DamageSource damageSource, float damage);
    @Shadow protected abstract float getDamageAfterMagicAbsorb(DamageSource damageSource, float damage);
    @Shadow public abstract float getAbsorptionAmount();
    @Shadow public abstract void setAbsorptionAmount(float absorptionAmount);
    @Shadow public abstract @Nullable LivingEntity getKillCredit();
    @Shadow public abstract void stopUsingItem();
    @Shadow protected abstract void handleKillingBlow();
    @Shadow protected abstract void dropAllDeathLoot(ServerLevel level, DamageSource source);
    @Shadow protected abstract void createWitherRose(@Nullable LivingEntity killer);
    @Shadow public abstract boolean removeAllEffects();
    @Shadow protected abstract boolean canRandomlyTeleportTo(double x, double y, double z);
    @Shadow public abstract boolean onClimbable();

    @Unique private org.bukkit.event.entity.@Nullable EntityDamageEvent fission$pendingDamageEvent;
    @Unique private boolean fission$actuallyHurtResult;

    // CraftBukkit - this.setHealth(this.getMaxHealth()) inlined and simplified to skip the instanceof check for Player, as getBukkitEntity() is not initialized in constructor
    @org.spongepowered.asm.mixin.injection.Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setHealth(F)V"))
    private void fission$initHealth(LivingEntity self, float health) {
        this.entityData.set(DATA_HEALTH_ID, this.getMaxHealth());
    }

    // CraftBukkit start - visibility API
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "checkFallDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private int fission$fallParticlesSource(ServerLevel level, net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, int count, double xd, double yd, double zd, double speed,
                                            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
        if ((Object) this instanceof ServerPlayer) {
            return level.sendParticlesSource(this, particle, false, false, x, y, z, count, xd, yd, zd, speed);
        }
        return original.call(level, particle, x, y, z, count, xd, yd, zd, speed);
    }
    // CraftBukkit end - visibility API

    @org.spongepowered.asm.mixin.injection.Redirect(method = "tickDeath", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void fission$tickDeathCause(LivingEntity self, Entity.RemovalReason reason) {
        this.remove(reason, org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
    }

    @Inject(method = "shouldDiscardFriction", at = @At("HEAD"), cancellable = true)
    private void fission$frictionApi(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(!this.frictionState.toBooleanOrElse(!this.discardFriction)); // Paper - Friction API
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "onEquipItem(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isSilent()Z"))
    private boolean fission$silentEquip(boolean silent) {
        return silent || this.fission$silentEquip; // CraftBukkit - && !silent
    }

    @Inject(method = "triggerOnDeathMobEffects", at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V"))
    private void fission$deathEffectsEvent(ServerLevel level, Entity.RemovalReason reason, CallbackInfo ci) {
        this.removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.DEATH); // CraftBukkit
    }

    @Inject(method = "addAdditionalSaveData", at = @At("HEAD"))
    private void fission$saveFriction(net.minecraft.world.level.storage.ValueOutput output, CallbackInfo ci) {
        // Paper start - Friction API
        if (this.frictionState != net.kyori.adventure.util.TriState.NOT_SET) {
            output.putString("Paper.FrictionState", this.frictionState.toString());
        }
        // Paper end - Friction API
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "readAdditionalSaveData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;internalSetAbsorptionAmount(F)V"))
    private float fission$absorptionNaN(float absorptionAmount) {
        // Paper start - Check for NaN
        if (Float.isNaN(absorptionAmount)) {
            absorptionAmount = 0;
        }
        return absorptionAmount;
        // Paper end - Check for NaN
    }

    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void fission$loadFriction(net.minecraft.world.level.storage.ValueInput input, CallbackInfo ci) {
        // Paper start - Friction API
        input.getString("Paper.FrictionState").ifPresent(frictionState -> {
            try {
                this.frictionState = net.kyori.adventure.util.TriState.valueOf(frictionState);
            } catch (Exception ignored) {
                org.slf4j.LoggerFactory.getLogger(LivingEntity.class).error("Unknown friction state {} for {}", frictionState, this);
            }
        });
        // Paper end - Friction API
    }

    @Inject(method = "readAdditionalSaveData", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setHealth(F)V"))
    private void fission$loadMaxHealth(net.minecraft.world.level.storage.ValueInput input, CallbackInfo ci) {
        // CraftBukkit start
        input.read("Bukkit.MaxHealth", com.mojang.serialization.Codec.DOUBLE).ifPresent(maxHealth -> {
            ((LivingEntity) (Object) this).getAttribute(Attributes.MAX_HEALTH).setBaseValue(maxHealth);
        });
        // CraftBukkit end
    }

    @Inject(method = "lambda$readAdditionalSaveData$0", at = @At("HEAD"), cancellable = true)
    private void fission$sleepingPosDesync(net.minecraft.core.BlockPos sleepingPos, CallbackInfo ci) {
        // Paper - The sleeping pos will always also set the actual pos, so a desync suggests something is wrong
        if (!(this.position().distanceToSqr(sleepingPos.getX(), sleepingPos.getY(), sleepingPos.getZ()) < Mth.square(16))) {
            ci.cancel();
        }
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void fission$dropEvent(ItemStack itemStack, boolean thrownFromHand, Prediction prediction, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<ItemEntity> cir) {
        cir.setReturnValue(this.drop(itemStack, thrownFromHand, prediction, true, null)); // Paper - Extend dropItem API
    }

    // CraftBukkit start - effect events while ticking effects
    @Inject(method = "tickEffects", at = @At(value = "INVOKE", target = "Ljava/util/Map;keySet()Ljava/util/Set;"))
    private void fission$tickingEffectsStart(CallbackInfo ci) {
        this.tickingEffects = true; // CraftBukkit
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "tickEffects", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;remove()V"))
    private void fission$effectExpiration(Iterator<?> iterator, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original,
                                          @com.llamalad7.mixinextras.sugar.Share("expireCancelled") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef expireCancelled,
                                          @com.llamalad7.mixinextras.sugar.Local MobEffectInstance effect) {
        EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent((LivingEntity) (Object) this, effect, null, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.EXPIRATION);
        if (event.isCancelled()) {
            expireCancelled.set(true);
            return;
        }
        expireCancelled.set(false);
        original.call(iterator);
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "tickEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;onEffectsRemoved(Ljava/util/Collection;)V"))
    private boolean fission$effectExpirationRemoved(LivingEntity self, java.util.Collection<MobEffectInstance> effects,
                                                    @com.llamalad7.mixinextras.sugar.Share("expireCancelled") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef expireCancelled) {
        return !expireCancelled.get();
    }

    @Inject(method = "tickEffects", at = @At("TAIL"))
    private void fission$tickingEffectsEnd(CallbackInfo ci) {
        this.fission$runDeferredEffectUpdates();
    }

    @Unique
    private void fission$runDeferredEffectUpdates() {
        if (!this.tickingEffects) return;
        // CraftBukkit start
        this.tickingEffects = false;
        Runnable task;
        while ((task = this.deferredEffectUpdates.poll()) != null) {
            task.run();
        }
        // CraftBukkit end
    }
    // CraftBukkit end

    @Inject(method = "removeAllEffects()Z", at = @At("HEAD"), cancellable = true)
    private void fission$removeAllEffectsCause(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN)); // CraftBukkit
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void fission$addEffectCause(MobEffectInstance newEffect, @Nullable Entity source, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.addEffect(newEffect, source, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN)); // CraftBukkit
    }

    @Inject(method = "removeEffectNoUpdate(Lnet/minecraft/core/Holder;)Lnet/minecraft/world/effect/MobEffectInstance;", at = @At("HEAD"), cancellable = true)
    private void fission$removeEffectNoUpdateCause(Holder<MobEffect> effect, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<MobEffectInstance> cir) {
        cir.setReturnValue(this.removeEffectNoUpdate(effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN)); // CraftBukkit
    }

    @Inject(method = "removeEffect(Lnet/minecraft/core/Holder;)Z", at = @At("HEAD"), cancellable = true)
    private void fission$removeEffectCause(Holder<MobEffect> effect, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.removeEffect(effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN)); // CraftBukkit
    }

    @Inject(method = "heal(F)V", at = @At("HEAD"), cancellable = true)
    private void fission$healReason(float heal, CallbackInfo ci) {
        // CraftBukkit start - Delegate so we can handle providing a reason for health being regained
        this.heal(heal, EntityRegainHealthEvent.RegainReason.CUSTOM);
        ci.cancel();
    }

    @Inject(method = "getHealth", at = @At("HEAD"), cancellable = true)
    private void fission$unscaledHealth(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        // CraftBukkit start - Use unscaled health
        if ((Object) this instanceof ServerPlayer player) {
            cir.setReturnValue((float) player.getBukkitEntity().getHealth());
        }
        // CraftBukkit end
    }

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void fission$setHealth(float health, CallbackInfo ci) {
        // Paper start - Check for NaN
        if (Float.isNaN(health)) {
            health = this.getMaxHealth();
            if (this.valid) {
                System.err.println("[NAN-HEALTH] " + this.getScoreboardName() + " had NaN health set");
            }
        } // Paper end - Check for NaN
        // CraftBukkit start - Handle scaled health
        if ((Object) this instanceof ServerPlayer serverPlayer) {
            org.bukkit.craftbukkit.entity.CraftPlayer player = serverPlayer.getBukkitEntity();
            // Squeeze
            if (health < 0.0F) {
                player.setRealHealth(0.0);
            } else if (health > player.getMaxHealth()) {
                player.setRealHealth(player.getMaxHealth());
            } else {
                player.setRealHealth(health);
            }

            player.updateScaledHealth(false);
            ci.cancel();
            return;
        }
        // CraftBukkit end
        this.entityData.set(DATA_HEALTH_ID, Mth.clamp(health, 0.0F, this.getMaxHealth()));
        ci.cancel();
    }

    // ---- damage pipeline: hurtServer (replaced body), handleEntityDamage, actuallyHurt(level, source, dmg, event) ----

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void fission$hurtServer(ServerLevel level, DamageSource source, float damage, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.fission$paperHurtServer(level, source, damage));
    }

    @Unique
    private boolean fission$paperHurtServer(final ServerLevel level, final DamageSource source, float damage) {
        final LivingEntity self = (LivingEntity) (Object) this;
        if (self.isInvulnerableTo(level, source)) {
            return false;
        }

        if (this.isRemoved() || this.dead || this.getHealth() <= 0.0F) { // CraftBukkit - Don't allow entities that got set to dead/killed elsewhere to get damaged and die
            return false;
        }

        if (source.is(DamageTypeTags.IS_FIRE) && this.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) {
            return false;
        }

        if (this.isSleeping()) {
            this.stopSleeping();
        }

        this.noActionTime = 0;
        if (damage < 0.0F) {
            damage = 0.0F;
        }

        float originalDamage = damage;
        ItemStack itemInUse = this.getUseItem();
        float damageBlocked = this.applyItemBlocking(level, source, damage, true); // Paper
        // damage -= damageBlocked; // CraftBukkit - Moved into handleEntityDamage(DamageSource, float) to allow modification
        boolean blocked = damageBlocked > 0.0F;
        // CraftBukkit - freezing and helmet damage moved into handleEntityDamage(DamageSource, float)

        if (Float.isNaN(damage) || Float.isInfinite(damage)) {
            damage = Float.MAX_VALUE;
        }

        org.bukkit.event.entity.EntityDamageEvent event; // CraftBukkit // Paper - move this into the actual invuln check....
        boolean tookFullDamage = true;
        if (this.damageCooldownTime > (float) this.invulnerableDuration / 2.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) { // CraftBukkit - restore use of maxNoDamageTicks
            if (damage <= this.lastHurt) {
                return false;
            }

            // Paper start - only call damage event when actuallyHurt will be called - move call logic down
            event = this.fission$handleEntityDamage(source, damage, this.lastHurt); // Paper - fix invulnerability reduction in EntityDamageEvent - pass lastDamage reduction
            damage = this.fission$computeAmountFromEntityDamageEvent(event);
            // Paper end - only call damage event when actuallyHurt will be called - move call logic down

            // CraftBukkit start
            if (!this.actuallyHurt(level, source, (float) event.getFinalDamage(), event)) { // Paper - fix invulnerability reduction in EntityDamageEvent - no longer subtract lastHurt, that is part of the damage event calc now
                return false;
            }
            if ((Object) this instanceof ServerPlayer && event.getDamage() == 0 && originalDamage == 0) return false; // Paper - revert to vanilla damage - players are not affected by damage that is 0 - skip damage if the vanilla damage is 0 and was not modified by plugins in the event.
            // CraftBukkit end
            this.lastHurt = damage;
            tookFullDamage = false;
        } else {
            // Paper start - only call damage event when actuallyHurt will be called - move call logic down
            event = this.fission$handleEntityDamage(source, damage, 0); // Paper - fix invulnerability reduction in EntityDamageEvent - pass lastDamage reduction (none in this branch)
            damage = this.fission$computeAmountFromEntityDamageEvent(event);
            // Paper end - only call damage event when actuallyHurt will be called - move call logic down
            // CraftBukkit start
            if (!this.actuallyHurt(level, source, (float) event.getFinalDamage(), event)) {
                return false;
            }
            if ((Object) this instanceof ServerPlayer && event.getDamage() == 0 && originalDamage == 0) return false; // Paper - revert to vanilla damage - players are not affected by damage that is 0 - skip damage if the vanilla damage is 0 and was not modified by plugins in the event.
            this.lastHurt = damage;
            this.damageCooldownTime = this.invulnerableDuration; // CraftBukkit - restore use of maxNoDamageTicks
            // this.actuallyHurt(level, source, damage);
            // CraftBukkit end
            this.hurtDuration = 10;
            this.hurtTime = this.hurtDuration;
        }

        this.resolveMobResponsibleForDamage(source);
        this.resolvePlayerResponsibleForDamage(source);
        if (tookFullDamage) {
            BlocksAttacks blocksAttacks = itemInUse.get(DataComponents.BLOCKS_ATTACKS);
            if (blocked && blocksAttacks != null) {
                blocksAttacks.onBlocked(level, self);
            } else {
                level.broadcastDamageEvent(self, source);
            }

            if (!source.is(DamageTypeTags.NO_IMPACT) && !blocked) { // CraftBukkit - Prevent marking hurt if the damage is blocked
                this.markHurt();
            }

            boolean fullyBlocked = blocked && damage <= 0.0F;
            if (!source.is(DamageTypeTags.NO_KNOCKBACK) && !fullyBlocked) {
                this.dealDefaultKnockback(source, damage, blocked);
            }
        }

        if (this.isDeadOrDying()) {
            if (!this.checkTotemDeathProtection(source)) {
                this.silentDeath = !tookFullDamage; // Paper - moved into CraftEventFactory event caller for configurable death event

                this.die(source);
                this.silentDeath = true; // Paper
            }
        } else if (tookFullDamage) {
            this.playHurtSound(source);
            this.playSecondaryHurtSound(source);
        }

        boolean success = !blocked; // CraftBukkit - Ensure to return false if damage is blocked
        if (success) {
            this.lastDamageSource = source;
            this.lastDamageStamp = this.level().getGameTime();

            for (MobEffectInstance effect : this.getActiveEffects()) {
                effect.onMobHurt(level, self, source, damage);
            }
        }

        if ((Object) this instanceof ServerPlayer serverPlayer) {
            net.minecraft.advancements.triggers.CriteriaTriggers.ENTITY_HURT_PLAYER.trigger(serverPlayer, source, originalDamage, damage, blocked);
            if (damageBlocked > 0.0F && damageBlocked < 3.4028235E37F) {
                serverPlayer.awardStat(net.minecraft.stats.Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(damageBlocked * 10.0F));
            }
        }

        if (source.getEntity() instanceof ServerPlayer sourcePlayer) {
            net.minecraft.advancements.triggers.CriteriaTriggers.PLAYER_HURT_ENTITY.trigger(sourcePlayer, self, source, originalDamage, damage, blocked);
        }

        return success;
    }


    // Paper start - only call damage event when actuallyHurt will be called - move out amount computation logic
    @Unique
    private float fission$computeAmountFromEntityDamageEvent(final org.bukkit.event.entity.EntityDamageEvent event) {
        // Taken from hurtServer()'s craftbukkit diff.
        float amount = 0;
        amount += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BASE);
        amount += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING);
        amount += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.FREEZING);
        amount += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.HARD_HAT);
        return amount;
    }
    // Paper end - only call damage event when actuallyHurt will be called - move out amount computation logic

    // CraftBukkit start
    @Unique
    private org.bukkit.event.entity.EntityDamageEvent fission$handleEntityDamage(final DamageSource damagesource, float damage, final float invulnerabilityRelatedLastDamage) { // Paper - fix invulnerability reduction in EntityDamageEvent
        final LivingEntity self = (LivingEntity) (Object) this;
        float originalDamage = damage;
        // Paper start - fix invulnerability reduction in EntityDamageEvent
        final com.google.common.base.Function<Double, Double> invulnerabilityReductionEquation = mod -> {
            if (invulnerabilityRelatedLastDamage == 0) return 0.0; // no last damage, no reduction
            // last damage existed, this means the reduction *technically* is (new damage - last damage).
            // If the event damage was changed to something less than invul damage, hard lock it at 0.
            //
            // Cast the passed in double down to a float as double -> float -> double is lossy.
            // If last damage is a (float) 3.2 (since the events use doubles), we cannot compare
            // the new damage value of this damage instance by upcasting it again to a double as 3.2 != (double) (float) 3.2.
            if (mod.floatValue() < invulnerabilityRelatedLastDamage) return 0.0;
            return (double) -invulnerabilityRelatedLastDamage;
        };
        final float originalInvulnerabilityReduction = invulnerabilityReductionEquation.apply((double) damage).floatValue();
        damage += originalInvulnerabilityReduction;
        // Paper end - fix invulnerability reduction in EntityDamageEvent

        com.google.common.base.Function<Double, Double> freezing = mod -> {
            if (damagesource.is(DamageTypeTags.IS_FREEZING) && self.is(net.minecraft.tags.EntityTypeTags.FREEZE_HURTS_EXTRA_TYPES)) {
                return -(mod - mod * 5.0F);
            }
            return -0.0;
        };
        float freezingModifier = freezing.apply((double) damage).floatValue();
        damage += freezingModifier;

        com.google.common.base.Function<Double, Double> hardHat = mod -> {
            if (damagesource.is(DamageTypeTags.DAMAGES_HELMET) && !self.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                return -(mod - mod * 0.75F);
            }
            return -0.0;
        };
        float hardHatModifier = hardHat.apply((double) damage).floatValue();
        damage += hardHatModifier;

        com.google.common.base.Function<Double, Double> blocking = mod -> {
            if (!self.canBlockAttack(damagesource, mod.floatValue())) {
                return 0D;
            }
            return (double) -self.resolveBlockedDamage(damagesource, mod.floatValue());
        };
        float blockingModifier = blocking.apply((double) damage).floatValue();
        damage += blockingModifier;

        com.google.common.base.Function<Double, Double> armor = mod -> -(mod - this.getDamageAfterArmorAbsorb(damagesource, mod.floatValue()));
        float armorModifier = armor.apply((double) damage).floatValue();
        damage += armorModifier;

        com.google.common.base.Function<Double, Double> resistance = mod -> {
            if (!damagesource.is(DamageTypeTags.BYPASSES_EFFECTS) && self.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE) && !damagesource.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
                int absorbValue = (self.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE).getAmplifier() + 1) * 5;
                int absorb = 25 - absorbValue;
                float v = mod.floatValue() * (float) absorb;

                return -(mod - Math.max(v / 25.0F, 0.0F));
            }
            return -0.0;
        };
        float resistanceModifier = resistance.apply((double) damage).floatValue();
        damage += resistanceModifier;

        com.google.common.base.Function<Double, Double> magic = mod -> -(mod - this.getDamageAfterMagicAbsorb(damagesource, mod.floatValue()));
        float magicModifier = magic.apply((double) damage).floatValue();
        damage += magicModifier;

        com.google.common.base.Function<Double, Double> absorption = mod -> -(Math.max(mod - Math.max(mod - self.getAbsorptionAmount(), 0.0F), 0.0F));
        float absorptionModifier = absorption.apply((double) damage).floatValue();

        // Paper start - fix invulnerability reduction in EntityDamageEvent
        return CraftEventFactory.handleLivingEntityDamageEvent(self, damagesource, originalDamage, freezingModifier, hardHatModifier, blockingModifier, armorModifier, resistanceModifier, magicModifier, absorptionModifier, freezing, hardHat, blocking, armor, resistance, magic, absorption, (damageModifierDoubleMap, damageModifierFunctionMap) -> {
            damageModifierFunctionMap.put(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.INVULNERABILITY_REDUCTION, invulnerabilityReductionEquation);
            damageModifierDoubleMap.put(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.INVULNERABILITY_REDUCTION, (double) originalInvulnerabilityReduction);
        });
        // Paper end - fix invulnerability reduction in EntityDamageEvent
    }

    // CraftBukkit: Paper's actuallyHurt(level, source, dmg, event) (void -> boolean). FissionMC: the event travels through vanilla
    // actuallyHurt(level, source, dmg) (which subclasses override), whose body is replaced by fission$paperActuallyHurt.
    protected boolean actuallyHurt(ServerLevel level, final DamageSource source, float dmg, final org.bukkit.event.entity.EntityDamageEvent event) {
        final org.bukkit.event.entity.EntityDamageEvent prev = this.fission$pendingDamageEvent;
        this.fission$pendingDamageEvent = event;
        this.fission$actuallyHurtResult = true;
        try {
            this.actuallyHurt(level, source, dmg);
            return this.fission$actuallyHurtResult;
        } finally {
            this.fission$pendingDamageEvent = prev;
        }
    }

    @Shadow protected abstract void actuallyHurt(ServerLevel level, DamageSource source, float dmg);

    @Inject(method = "actuallyHurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)V", at = @At("HEAD"), cancellable = true)
    private void fission$actuallyHurt(ServerLevel level, DamageSource source, float dmg, CallbackInfo ci) {
        if (this.fission$pendingDamageEvent == null) return; // not reached through hurtServer: vanilla
        ci.cancel();
        this.fission$actuallyHurtResult = this.fission$paperActuallyHurt(level, source, dmg, this.fission$pendingDamageEvent);
    }

    @Override
    public boolean fission$paperActuallyHurtPending(ServerLevel level, DamageSource source, float dmg) {
        if (this.fission$pendingDamageEvent == null) return false;
        this.fission$actuallyHurtResult = this.fission$paperActuallyHurt(level, source, dmg, this.fission$pendingDamageEvent);
        return true;
    }

    @Unique
    private boolean fission$paperActuallyHurt(ServerLevel level, final DamageSource source, float dmg, final org.bukkit.event.entity.EntityDamageEvent event) {
        final LivingEntity self = (LivingEntity) (Object) this;
        if (!self.isInvulnerableTo(level, source)) {
            if (event.isCancelled()) {
                return false;
            }
            float originalDamage = (float) event.getDamage();

            // Resistance
            if (event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.RESISTANCE) < 0) {
                float damageResisted = (float) -event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.RESISTANCE);
                if (damageResisted > 0.0F && damageResisted < 3.4028235E37F) {
                    if (self instanceof ServerPlayer) {
                        ((ServerPlayer) self).awardStat(net.minecraft.stats.Stats.DAMAGE_RESISTED, Math.round(damageResisted * 10.0F));
                    } else if (source.getEntity() instanceof ServerPlayer) {
                        ((ServerPlayer) source.getEntity()).awardStat(net.minecraft.stats.Stats.DAMAGE_DEALT_RESISTED, Math.round(damageResisted * 10.0F));
                    }
                }
            }

            // Apply damage to helmet
            if (source.is(DamageTypeTags.DAMAGES_HELMET) && !this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                float helmetDamage = originalDamage;
                helmetDamage += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.INVULNERABILITY_REDUCTION);
                helmetDamage += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING);
                helmetDamage += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.FREEZING);
                this.hurtHelmet(source, helmetDamage);
            }

            // Apply damage to armor
            if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
                float armorDamage = originalDamage;
                armorDamage += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.INVULNERABILITY_REDUCTION);
                armorDamage += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING);
                armorDamage += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.FREEZING);
                armorDamage += (float) event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.HARD_HAT);
                this.hurtArmor(source, armorDamage);
            }

            // Apply blocking code
            if (event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING) < 0) {
                this.blockingItemEffects(level, source, originalDamage, (float) -event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING));
            }

            boolean human = self instanceof net.minecraft.world.entity.player.Player;
            float absorptionModifier = (float) -event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.ABSORPTION);
            this.setAbsorptionAmount(Math.max(this.getAbsorptionAmount() - absorptionModifier, 0.0F));
            float absorbedDamage = absorptionModifier;

            if (absorbedDamage > 0.0F && absorbedDamage < 3.4028235E37F && self instanceof net.minecraft.world.entity.player.Player player) {
                player.awardStat(net.minecraft.stats.Stats.DAMAGE_ABSORBED, Math.round(absorbedDamage * 10.0F));
            }
            // CraftBukkit end
            if (absorbedDamage > 0.0F && absorbedDamage < 3.4028235E37F && source.getEntity() instanceof ServerPlayer serverPlayer) {
                serverPlayer.awardStat(net.minecraft.stats.Stats.DAMAGE_DEALT_ABSORBED, Math.round(absorbedDamage * 10.0F));
            }

            // CraftBukkit start
            if (dmg > 0.0F || !human) {
                if (human) {
                    // PAIL: Be sure to drag all this code from the Player subclass each update.
                    ((net.minecraft.world.entity.player.Player) self).causeFoodExhaustion(source.getFoodExhaustion(), org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.DAMAGED); // CraftBukkit - EntityExhaustionEvent
                    if (dmg < 3.4028235E37F) {
                        ((net.minecraft.world.entity.player.Player) self).awardStat(net.minecraft.stats.Stats.DAMAGE_TAKEN, Math.round(dmg * 10.0F));
                    }
                }
                // CraftBukkit end
                this.getCombatTracker().recordDamage(source, dmg);
                this.setHealth(this.getHealth() - dmg);
                // CraftBukkit start
                if (!human) {
                    this.setAbsorptionAmount(this.getAbsorptionAmount() - dmg);
                }
                this.gameEvent(GameEvent.ENTITY_DAMAGE);
                return true;
            } else {
                // Duplicate triggers if blocking
                if (event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING) < 0) {
                    if (self instanceof ServerPlayer serverPlayer) {
                        net.minecraft.advancements.triggers.CriteriaTriggers.ENTITY_HURT_PLAYER.trigger(serverPlayer, source, originalDamage, dmg, true); // Paper - fix taken/dealt param order
                        float damageBlocked = (float) -event.getDamage(org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BLOCKING);
                        if (damageBlocked > 0.0F && damageBlocked < 3.4028235E37F) {
                            serverPlayer.awardStat(net.minecraft.stats.Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(damageBlocked * 10.0F));
                        }
                    }

                    if (source.getEntity() instanceof ServerPlayer sourcePlayer) {
                        net.minecraft.advancements.triggers.CriteriaTriggers.PLAYER_HURT_ENTITY.trigger(sourcePlayer, self, source, originalDamage, dmg, true); // Paper - fix taken/dealt param order
                    }

                    return !io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.skipVanillaDamageTickWhenShieldBlocked; // Paper - this should always return true, however expose an unsupported setting to flip this to false to enable "shield stunning".
                } else {
                    return true; // Paper - return false ONLY if event was cancelled
                }
                // CraftBukkit end
            }
        }
        return true; // CraftBukkit // Paper - return false ONLY if event was cancelled
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "getDamageAfterArmorAbsorb", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hurtArmor(Lnet/minecraft/world/damagesource/DamageSource;F)V"))
    private boolean fission$noArmorDamageInAbsorb(LivingEntity self, DamageSource source, float damage) {
        return false; // this.hurtArmor(damageSource, damage); // CraftBukkit - actuallyHurt(DamageSource, float, EntityDamageEvent) for damage handling
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "getDamageAfterMagicAbsorb", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean fission$noResistanceInMagicAbsorb(boolean hasEffect) {
        return false; // CraftBukkit - Moved to handleEntityDamage(DamageSource, float)
    }

    // Paper start - Check distance in entity interactions; see for loop in knockback method / knockback events
    @Inject(method = "dealDefaultKnockback", at = @At("HEAD"), cancellable = true)
    private void fission$dealDefaultKnockback(DamageSource source, float damage, boolean blocked, CallbackInfo ci) {
        ci.cancel();
        double xd = 0.0;
        double zd = 0.0;
        if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair knockbackDirection = projectile.calculateHorizontalHurtKnockbackDirection((LivingEntity) (Object) this, source);
            xd = -knockbackDirection.leftDouble();
            zd = -knockbackDirection.rightDouble();
        } else if (source.getSourcePosition() != null) {
            xd = source.getSourcePosition().x() - this.getX();
            zd = source.getSourcePosition().z() - this.getZ();
        }

        // Paper start - Check distance in entity interactions; see for loop in knockback method
        if (Math.abs(xd) > 200) {
            xd = Math.random() - Math.random();
        }
        if (Math.abs(zd) > 200) {
            zd = Math.random() - Math.random();
        }
        // Paper end - Check distance in entity interactions

        this.knockback(0.4F, xd, zd, source, damage, source.getDirectEntity(), source.getDirectEntity() == null ? io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.DAMAGE : io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK); // CraftBukkit // Paper - knockback events
        if (!blocked) {
            ((LivingEntity) (Object) this).indicateDamage(xd, zd);
        }
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "blockedByItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V"))
    private void fission$shieldKnockback(LivingEntity defender, double power, double xd, double zd, DamageSource source, float damage) {
        defender.knockback(power, xd, zd, source, damage, this, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.SHIELD_BLOCK); // CraftBukkit // Paper - fix attacker & knockback events
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"))
    private void fission$attackKnockback0(LivingEntity target, double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect) {
        target.knockback(power, xd, zd, source, damage, comesFromEffect, this, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK); // Paper - knockback events
    }
    // Paper end - knockback events

    // replaced body (CraftBukkit - EntityResurrectEvent)
    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void fission$resurrectEvent(DamageSource killingDamage, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        final LivingEntity self = (LivingEntity) (Object) this;
        if (killingDamage.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            cir.setReturnValue(false);
            return;
        }

        ItemStack protectionItem = null;
        net.minecraft.world.item.component.DeathProtection protection = null;

        // CraftBukkit start
        InteractionHand usedHand = null;
        ItemStack itemStack = ItemStack.EMPTY;
        for (InteractionHand hand : InteractionHand.values()) {
            itemStack = this.getItemInHand(hand);
            protection = itemStack.get(DataComponents.DEATH_PROTECTION);
            if (protection != null) {
                usedHand = hand;
                protectionItem = itemStack.copy();
                // itemStack.shrink(1);
                break;
            }
        }

        final org.bukkit.inventory.EquipmentSlot handSlot = (usedHand != null) ? org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(usedHand) : null;
        final org.bukkit.event.entity.EntityResurrectEvent event = new org.bukkit.event.entity.EntityResurrectEvent(this.getBukkitEntity(), handSlot);
        event.setCancelled(protectionItem == null);
        this.level().getCraftServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            // Set death protection to null as the event was cancelled. Prevent any attempt at resurrection.
            protection = null;
        } else {
            if (!itemStack.isEmpty() && protectionItem != null) { // Paper - only reduce item if actual totem was found
                itemStack.shrink(1);
            }
            // Paper start - fix NPE when pre-cancelled EntityResurrectEvent is uncancelled
            // restore the previous behavior in that case by defaulting to vanilla's totem of undying effect
            if (protection == null) {
                protection = net.minecraft.world.item.component.DeathProtection.TOTEM_OF_UNDYING;
            }
            // Paper end - fix NPE when pre-cancelled EntityResurrectEvent is uncancelled
            if (protectionItem != null && self instanceof final ServerPlayer player) {
                // CraftBukkit end
                player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(protectionItem.getItem()));
                net.minecraft.advancements.triggers.CriteriaTriggers.USED_TOTEM.trigger(player, protectionItem);
                protectionItem.causeUseVibration(self, GameEvent.ITEM_INTERACT_FINISH);
            }

            this.setHealth(1.0F);
            protection.applyEffects(protectionItem, self);
            this.level().broadcastEntityEvent(self, (byte) 35);
        }

        cir.setReturnValue(protection != null);
    }

    // replaced body (Paper - cancellable EntityDeathEvent, drops collected before the event, post-death tasks)
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void fission$die(DamageSource source, CallbackInfo ci) {
        ci.cancel();
        final LivingEntity self = (LivingEntity) (Object) this;
        if (!this.isRemoved() && !this.dead) {
            Entity sourceEntity = source.getEntity();
            LivingEntity killer = this.getKillCredit();
            Runnable afterEvent = () -> { // Paper
                if (killer != null) {
                    killer.awardKillScore(self, source);
                }

                if (this.isSleeping()) {
                    this.stopSleeping();
                }

                this.stopUsingItem();
                if (!this.level().isClientSide() && this.hasCustomName()) {
                    if (org.spigotmc.SpigotConfig.logNamedDeaths) org.slf4j.LoggerFactory.getLogger(LivingEntity.class).info("Named entity {} died: {}", self, this.getCombatTracker().getDeathMessage().getString()); // Spigot
                }

                this.handleKillingBlow();
            }; // Paper
            if (this.level() instanceof ServerLevel serverLevel) {
                // Paper start
                this.deathDropItems = new java.util.ArrayList<>();
                if (sourceEntity != null) {
                    sourceEntity.killedEntityPreEvent(serverLevel, self, source); // safe to ignore the boolean if always true
                }
                this.dropAllDeathLoot(serverLevel, source);
                org.bukkit.event.entity.EntityDeathEvent deathEvent = CraftEventFactory.callEntityDeathEvent(serverLevel, self, source, this.deathDropItems, true);
                this.deathDropItems = null;
                if (deathEvent.isCancelled()) {
                    this.dead = false;
                    this.postDeathEventTasks.clear();
                    this.setHealth((float) deathEvent.getReviveHealth());
                    return;
                }

                afterEvent.run();
                // Paper end

                if (sourceEntity == null || sourceEntity.killedEntity(serverLevel, self, source)) {
                    this.gameEvent(GameEvent.ENTITY_DIE);
                    this.postDeathEventTasks.forEach(Runnable::run); // Paper
                    this.createWitherRose(killer);
                }

                this.postDeathEventTasks.clear(); // Paper
                this.level().broadcastEntityEvent(self, (byte) 3);
            }

            this.setPose(net.minecraft.world.entity.Pose.DYING);
        }
    }

    // replaced body (CraftBukkit - EntityBlockFormEvent / EntityDropItemEvent for the wither rose)
    @Inject(method = "createWitherRose", at = @At("HEAD"), cancellable = true)
    private void fission$createWitherRose(@Nullable LivingEntity killer, CallbackInfo ci) {
        ci.cancel();
        if (this.level() instanceof ServerLevel serverLevel) {
            boolean var6 = false;
            if (killer instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
                if (serverLevel.getGameRules().get(GameRules.MOB_GRIEFING)) {
                    net.minecraft.core.BlockPos pos = this.blockPosition();
                    net.minecraft.world.level.block.state.BlockState state = net.minecraft.world.level.block.Blocks.WITHER_ROSE.defaultBlockState();
                    if (this.level().getBlockState(pos).isAir() && state.canSurvive(this.level(), pos)) {
                        var6 = CraftEventFactory.handleBlockFormEvent(this.level(), pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL, this); // CraftBukkit - call EntityBlockFormEvent for Wither Rose
                    }
                }

                if (!var6) {
                    ItemEntity itemEntity = new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), new ItemStack(net.minecraft.world.item.Items.WITHER_ROSE));
                    // CraftBukkit start
                    org.bukkit.event.entity.EntityDropItemEvent event = new org.bukkit.event.entity.EntityDropItemEvent(this.getBukkitEntity(), (org.bukkit.entity.Item) itemEntity.getBukkitEntity());
                    if (!event.callEvent()) {
                        return;
                    }
                    // CraftBukkit end
                    this.level().addFreshEntity(itemEntity);
                }
            }
        }
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "dropAllDeathLoot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;dropExperience(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)V"))
    private boolean fission$deferExperience(LivingEntity self, ServerLevel level, @Nullable Entity killer) {
        this.postDeathEventTasks.add(() -> this.dropExperience(level, killer)); // Paper
        return false;
    }

    @Shadow protected abstract void dropExperience(ServerLevel level, @Nullable Entity killer);

    @Inject(method = "dropExperience", at = @At("HEAD"), cancellable = true)
    private void fission$dropExpToDrop(ServerLevel level, @Nullable Entity entity, CallbackInfo ci) {
        ci.cancel();
        // CraftBukkit start - Update getExpReward() above if the removed if() changes!
        if (!((Object) this instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) { // CraftBukkit - SPIGOT-2420: Special case ender dragon will drop the xp over time
            net.minecraft.world.entity.ExperienceOrb.awardWithDirection(level, this.position(), Vec3.ZERO, this.expToDrop, (Object) this instanceof ServerPlayer ? org.bukkit.entity.ExperienceOrb.SpawnReason.PLAYER_DEATH : org.bukkit.entity.ExperienceOrb.SpawnReason.ENTITY_DEATH, entity, this); // Paper
            this.expToDrop = 0;
        }
        // CraftBukkit end
    }

    @Inject(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V", at = @At("HEAD"), cancellable = true)
    private void fission$knockback5(double power, double xd, double zd, DamageSource source, float damage, CallbackInfo ci) {
        // Paper start - knockback events
        this.knockback(power, xd, zd, source, damage, null, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.UNKNOWN);
        ci.cancel();
    }

    @Inject(method = "skipDropExperience", at = @At("TAIL"))
    private void fission$skipDropExperience(CallbackInfo ci) {
        this.expToDrop = 0; // Paper
    }

    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "isAlive", at = @At("RETURN"))
    private boolean fission$isAliveNotDead(boolean alive) {
        return alive && !this.dead; // Paper - Check this.dead
    }

    // CraftBukkit start - fall damage: hurtServer first, abort when it fails
    @Inject(method = "causeFallDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;resetCurrentImpulseContext()V", ordinal = 1), cancellable = true)
    private void fission$fallDamageFirst(double fallDistance, float damageModifier, DamageSource damageSource, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir,
                                         @com.llamalad7.mixinextras.sugar.Local(ordinal = 0) int dmg) {
        if (!this.hurtServer((ServerLevel) this.level(), damageSource, (float) dmg)) {
            cir.setReturnValue(true);
        }
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "causeFallDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)V"))
    private boolean fission$fallDamageMovedUp(LivingEntity self, DamageSource source, float amount) {
        return false; // this.hurt(damageSource, dmg); // CraftBukkit - moved up
    }
    // CraftBukkit end

    @Inject(method = "setArrowCount(I)V", at = @At("HEAD"), cancellable = true)
    private void fission$arrowCountEvent(int count, CallbackInfo ci) {
        // CraftBukkit start
        this.setArrowCount(count, false);
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.ModifyConstant(method = "handleDamageEvent", constant = @org.spongepowered.asm.mixin.injection.Constant(intValue = 20))
    private int fission$invulnerableDurationClient(int original) {
        return this.invulnerableDuration; // Paper - configurable invulnerable duration
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "onBelowWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)V"))
    private float fission$voidDamageAmount(float amount) {
        return this.level().getWorld().getVoidDamageAmount(); // Paper - use configured void damage amount
    }

    // Paper start - Prevent excessive velocity through repeated crits
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private boolean fission$canCrit(LivingEntity self, Vec3 movement) {
        long time = System.nanoTime();
        boolean canCrit = true;
        if ((Object) this instanceof net.minecraft.world.entity.player.Player) {
            canCrit = false;
            if (time - this.lastJumpTime > (long) (0.250e9)) {
                this.lastJumpTime = time;
                canCrit = true;
            }
        }
        return canCrit;
    }
    // Paper end - Prevent excessive velocity through repeated crits

    @Inject(method = "stopFallFlying", at = @At("HEAD"), cancellable = true)
    private void fission$stopGlideEvent(CallbackInfo ci) {
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callToggleGlideEvent((LivingEntity) (Object) this, false).isCancelled()) ci.cancel(); // Paper
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;jumpFromGround()V"))
    private void fission$jumpEvent(LivingEntity self, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original,
                                   @com.llamalad7.mixinextras.sugar.Share("jumpCancelled") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef jumpCancelled) {
        if (new com.destroystokyo.paper.event.entity.EntityJumpEvent(this.getBukkitEntity()).callEvent()) { // Paper - Entity Jump API
            jumpCancelled.set(false);
            original.call(self);
        } else {
            jumpCancelled.set(true);
            self.setJumping(false); // Paper - Entity Jump API; setJumping(false) stops a potential loop
        }
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "aiStep", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;noJumpDelay:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD, ordinal = 1))
    private boolean fission$jumpDelayIfJumped(LivingEntity self, int value, @com.llamalad7.mixinextras.sugar.Share("jumpCancelled") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef jumpCancelled) {
        return !jumpCancelled.get();
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setTicksFrozen(I)V"))
    private boolean fission$freezeLockAiStep(LivingEntity self, int ticks) {
        return !this.freezeLocked; // Paper - Freeze Tick Lock API
    }

    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;pushEntities()V", shift = At.Shift.AFTER))
    private void fission$entityMoveEvent(CallbackInfo ci) {
        // Paper start - Add EntityMoveEvent
        if (this.level() instanceof ServerLevel serverLevel && serverLevel.hasEntityMoveEvent && !((Object) this instanceof net.minecraft.world.entity.player.Player)) {
            if (this.xo != this.getX() || this.yo != this.getY() || this.zo != this.getZ() || this.yRotO != this.getYRot() || this.xRotO != this.getXRot()) {
                org.bukkit.Location from = new org.bukkit.Location(this.level().getWorld(), this.xo, this.yo, this.zo, this.yRotO, this.xRotO);
                org.bukkit.Location to = new org.bukkit.Location(this.level().getWorld(), this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot());
                io.papermc.paper.event.entity.EntityMoveEvent event = new io.papermc.paper.event.entity.EntityMoveEvent(this.getBukkitEntity(), from, to.clone());
                if (!event.callEvent()) {
                    this.absSnapTo(from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch());
                } else if (!to.equals(event.getTo())) {
                    this.absSnapTo(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ(), event.getTo().getYaw(), event.getTo().getPitch());
                }
            }
        }
        // Paper end - Add EntityMoveEvent
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "updateFallFlying", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setSharedFlag(IZ)V", ordinal = 0))
    private boolean fission$glideEventCannotGlide(LivingEntity self, int flag, boolean value) {
        return this.getSharedFlag(Entity.FLAG_FALL_FLYING) && !CraftEventFactory.callToggleGlideEvent(self, false).isCancelled(); // CraftBukkit
    }

    // Paper start - don't run getEntities if we're not going to use its result / Cap entity collisions
    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void fission$pushEntitiesEarlyExit(CallbackInfo ci) {
        if (!this.isPushable()) {
            ci.cancel();
            return;
        }

        net.minecraft.world.scores.Team team = this.getTeam();
        if (team != null && team.getCollisionRule() == net.minecraft.world.scores.Team.CollisionRule.NEVER) {
            ci.cancel();
            return;
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            int maxCramming = serverLevel.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
            if (maxCramming <= 0 && this.level().paperConfig().collisions.maxEntityCollisions <= 0) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "pushEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", ordinal = 1))
    private void fission$capCollisionsDecay(CallbackInfo ci) {
        this.numCollisions = Math.max(0, this.numCollisions - this.level().paperConfig().collisions.maxEntityCollisions); // Paper - Cap entity collisions
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "pushEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;doPush(Lnet/minecraft/world/entity/Entity;)V"))
    private boolean fission$capCollisions(LivingEntity self, Entity entity) {
        // Paper start - Cap entity collisions
        if (this.numCollisions >= this.level().paperConfig().collisions.maxEntityCollisions) {
            return false; // TODO(behaviour): Paper breaks out of the loop; here the remaining entities are skipped
        }

        ((EntityAccessorMixin) entity).fission$setNumCollisions(((EntityAccessorMixin) entity).fission$getNumCollisions() + 1);
        this.numCollisions++;
        return true;
        // Paper end - Cap entity collisions
    }
    // Paper end

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "stopRiding()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;dismountVehicle(Lnet/minecraft/world/entity/Entity;)V"))
    private boolean fission$dismountOnlyValid(LivingEntity self, Entity oldVehicle) {
        return oldVehicle.valid; // Paper - don't process on world gen
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "take", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;sendToTrackingPlayers(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/protocol/Packet;)V"))
    private void fission$takeSelf(net.minecraft.server.level.ServerChunkCache cache, Entity entity, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        cache.sendToTrackingPlayersAndSelf(entity, packet); // Paper - broadcast with collector as source
    }

    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "isPickable", at = @At("RETURN"))
    private boolean fission$pickableCollides(boolean pickable) {
        return pickable && this.collides; // CraftBukkit
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void fission$livingIsPushable(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        // Paper start - Climbing should not bypass cramming gamerule
        cir.setReturnValue(this.isCollidable(this.level().paperConfig().collisions.fixClimbingBypassingCrammingRule));
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "setAbsorptionAmount", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;internalSetAbsorptionAmount(F)V"))
    private float fission$absorptionSetNaN(float clamped, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) float absorptionAmount) {
        return !Float.isNaN(absorptionAmount) ? clamped : 0.0F; // Paper - Check for NaN
    }

    @Inject(method = "startUsingItem(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"), cancellable = true)
    private void fission$startUsingItem(InteractionHand hand, CallbackInfo ci) {
        // Paper start - Prevent consuming the wrong itemstack
        this.startUsingItem(hand, false);
        ci.cancel();
    }

    @Inject(method = "onAttack()V", at = @At("HEAD"))
    private void fission$onAttack(CallbackInfo ci) {
        this.onAttack(null); // Paper
    }

    // Paper start - lag compensate eating
    @Inject(method = "updateUsingItem", at = @At("RETURN"))
    private void fission$lagCompensateEating(ItemStack useItem, CallbackInfo ci) {
        // Paper: (--useItemRemaining == 0 || shouldLagCompensate) -- vanilla handled the first half, here the second
        if (this.useItemRemaining > 0 && !this.level().isClientSide() && !useItem.useOnRelease()) {
            // we add 1 to the expected time to avoid lag compensating when we should not
            final boolean shouldLagCompensate = this.useItem.has(DataComponents.FOOD) && this.eatStartTime != -1 && (System.nanoTime() - this.eatStartTime) > ((1L + this.totalEatTimeTicks) * 50L * (1000L * 1000L));
            if (shouldLagCompensate) {
                this.useItemRemaining = 0;
                this.completeUsingItem();
            }
        }
    }

    @Inject(method = "onSyncedDataUpdated", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;useItemRemaining:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD, ordinal = 1))
    private void fission$resetEatInUpdating(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        this.totalEatTimeTicks = 0;
        this.eatStartTime = -1L;
    }

    @Inject(method = "stopUsingItem", at = @At("RETURN"))
    private void fission$resetEatInStop(CallbackInfo ci) {
        this.totalEatTimeTicks = 0;
        this.eatStartTime = -1L;
    }
    // Paper end - lag compensate eating

    @Unique private InteractionHand fission$consumeHand;
    @Unique private boolean fission$consumeForceHeld;

    // CraftBukkit start - fire PlayerItemConsumeEvent
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "completeUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack fission$consumeEvent(ItemStack stack, Level level, LivingEntity self, com.llamalad7.mixinextras.injector.wrapoperation.Operation<ItemStack> original) {
        InteractionHand hand = this.getUsedItemHand();
        this.startUsingItem(hand, true); // Paper - Prevent consuming the wrong itemstack
        ItemStack result;
        org.bukkit.event.player.PlayerItemConsumeEvent event = null; // Paper
        if ((Object) this instanceof ServerPlayer serverPlayer) {
            org.bukkit.inventory.ItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(this.useItem);
            org.bukkit.inventory.EquipmentSlot handSlot = org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand);
            event = new org.bukkit.event.player.PlayerItemConsumeEvent((org.bukkit.entity.Player) this.getBukkitEntity(), craftItem, handSlot); // Paper
            this.level().getCraftServer().getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                // TODO(behaviour): Paper's Consumable#cancelUsingItem (resend of listener effects/active effects) is not ported
                serverPlayer.containerMenu.forceHeldSlot(hand);
                serverPlayer.getBukkitEntity().updateScaledHealth();
                return this.useItem; // vanilla then calls stopUsingItem(), as Paper does before returning - event is using an item, clear active item to reset its use
            }

            result = (craftItem.equals(event.getItem())) ? original.call(this.useItem, level, self) : original.call(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()), level, self);
        } else {
            result = original.call(this.useItem, level, self);
        }
        // Paper start - save the default replacement item and change it if necessary
        if (event != null && event.getReplacement() != null) {
            result = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getReplacement());
            this.fission$consumeForceHeld = true;
            this.fission$consumeHand = hand;
        }
        // Paper end
        return result;
    }

    @Inject(method = "completeUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;stopUsingItem()V"))
    private void fission$consumeForceHeld(CallbackInfo ci) {
        if (this.fission$consumeForceHeld) {
            this.fission$consumeForceHeld = false;
            if ((Object) this instanceof ServerPlayer player) player.containerMenu.forceHeldSlot(this.fission$consumeHand); // Paper - Fix inventory desync; Paper#13253
        }
    }
    // CraftBukkit end

    @Inject(method = "releaseUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;releaseUsing(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V"))
    private void fission$stopUsingEvent(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer) new io.papermc.paper.event.player.PlayerStopUsingItemEvent((org.bukkit.entity.Player) this.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(this.useItem), this.getTicksUsingItem()).callEvent(); // Paper - Add PlayerStopUsingItemEvent
    }

    // Paper start - EntityEquipmentChangedEvent, PlayerArmorChangeEvent
    // FissionMC: Paper fires the events inside the loop; here the previous items are snapshotted and the events fired from the result
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "collectEquipmentChanges")
    private @Nullable Map<EquipmentSlot, ItemStack> fission$equipmentChanged(Map<EquipmentSlot, ItemStack> lastEquipmentItems, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Map<EquipmentSlot, ItemStack>> original) {
        Map<EquipmentSlot, ItemStack> before = new java.util.EnumMap<>(EquipmentSlot.class);
        before.putAll(lastEquipmentItems);
        Map<EquipmentSlot, ItemStack> changed = original.call(lastEquipmentItems);
        if (changed != null) {
            Map<org.bukkit.inventory.EquipmentSlot, io.papermc.paper.event.entity.EntityEquipmentChangedEvent.EquipmentChange> equipmentChanges = new java.util.EnumMap<>(org.bukkit.inventory.EquipmentSlot.class);
            for (Map.Entry<EquipmentSlot, ItemStack> entry : changed.entrySet()) {
                EquipmentSlot slot = entry.getKey();
                ItemStack previous = before.getOrDefault(slot, ItemStack.EMPTY);
                final org.bukkit.inventory.ItemStack oldItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(previous);
                final org.bukkit.inventory.ItemStack newItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(entry.getValue());
                if ((Object) this instanceof ServerPlayer && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                    new com.destroystokyo.paper.event.player.PlayerArmorChangeEvent((org.bukkit.entity.Player) this.getBukkitEntity(), com.destroystokyo.paper.event.player.PlayerArmorChangeEvent.SlotType.valueOf(slot.name()), oldItem, newItem).callEvent();
                }
                equipmentChanges.put(org.bukkit.craftbukkit.CraftEquipmentSlot.getSlot(slot), new net.fissionmc.backend.world.entity.EquipmentChangeImpl(oldItem, newItem));
            }
            new io.papermc.paper.event.entity.EntityEquipmentChangedEvent(this.getBukkitEntity(), equipmentChanges).callEvent(); // Paper - EntityEquipmentChangedEvent
        }
        return changed;
    }
    // Paper end

    // TODO(behaviour): spin attack event, onItemPickup global player lookup, randomTeleport cause API/EntityTeleportEvent, rotation range checks
}
