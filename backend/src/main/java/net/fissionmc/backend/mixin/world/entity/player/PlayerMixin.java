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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

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

    @Shadow private int sleepCounter;
    @Shadow public abstract float getAttackStrengthScale(float a);
    @Shadow public abstract void resetOnlyAttackStrengthTicker();
    @Shadow protected abstract void removeEntitiesOnShoulder();

    // Paper start - Add PlayerDeepSleepEvent
    @Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;sleepCounter:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER))
    private void fission$deepSleep(CallbackInfo ci) {
        if (this.sleepCounter == Player.SLEEP_DURATION) {
            if (!new io.papermc.paper.event.player.PlayerDeepSleepEvent((org.bukkit.entity.Player) this.getBukkitEntity()).callEvent()) {
                this.sleepCounter = Integer.MIN_VALUE;
            }
        }
    }
    // Paper end - Add PlayerDeepSleepEvent

    @WrapOperation(method = "turtleHelmetTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"))
    private boolean fission$turtleCause(Player self, net.minecraft.world.effect.MobEffectInstance effect, Operation<Boolean> original) {
        return self.addEffect(effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.TURTLE_HELMET); // CraftBukkit
    }

    // CraftBukkit start - SPIGOT-7316: no longer passenger, dismount and return
    @WrapOperation(method = "rideTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;stopRiding()V"))
    private void fission$rideTickStop(Player self, Operation<Void> original) {
        original.call(self);
        if (!self.isPassenger()) {
            self.setShiftKeyDown(false);
        }
        // TODO(behaviour): Paper falls through to super.rideTick() when still a passenger after stopRiding(); vanilla returns
    }
    // CraftBukkit end

    // CraftBukkit start - Don't filter out 0 damage; peaceful returns false; shoulder entities are removed only if damaged
    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;removeEntitiesOnShoulder()V"))
    private void fission$noShoulderYet(Player self, Operation<Void> original) {
        // CraftBukkit - moved down
    }

    @ModifyConstant(method = "hurtServer", constant = @Constant(floatValue = 0.0F, ordinal = 1))
    private float fission$dontFilterZero(float zero) {
        return Float.NaN; // CraftBukkit - Don't filter out 0 damage
    }

    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Avatar;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean fission$hurtSuper(Player self, net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float damage, Operation<Boolean> original) {
        if (source.scalesWithDifficulty() && level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            return false; // CraftBukkit - damage = 0.0F -> return false
        }
        boolean damaged = original.call(self, level, source, damage);
        if (damaged) {
            this.removeEntitiesOnShoulder();
        }
        return damaged;
    }
    // CraftBukkit end

    // CraftBukkit start - Change to check OTHER player's scoreboard team according to API
    @Override
    public boolean doTeamsAllowDamage(Entity target) {
        // To summarize this method's logic, it's "Can parameter hurt this"
        org.bukkit.scoreboard.Team team;
        if (target instanceof net.minecraft.server.level.ServerPlayer thatPlayer) {
            team = thatPlayer.getBukkitEntity().getScoreboard().getPlayerTeam(thatPlayer.getBukkitEntity());
            if (team == null || team.allowFriendlyFire()) {
                return true;
            }
        } else {
            // This should never be called, but is implemented anyway
            org.bukkit.OfflinePlayer thisPlayer = target.level().getCraftServer().getOfflinePlayer(target.getScoreboardName());
            team = target.level().getCraftServer().getScoreboardManager().getMainScoreboard().getPlayerTeam(thisPlayer);
            if (team == null || team.allowFriendlyFire()) {
                return true;
            }
        }

        if ((Object) this instanceof net.minecraft.server.level.ServerPlayer self) {
            return !team.hasPlayer(self.getBukkitEntity());
        }
        return !team.hasPlayer(this.level().getCraftServer().getOfflinePlayer(this.getScoreboardName()));
    }
    // CraftBukkit end

    @Inject(method = "isImmobile", at = @At("RETURN"), cancellable = true)
    private void fission$immobileNotInWorld(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && (this.isRemoved() || !this.valid)) {
            cir.setReturnValue(true); // Paper - players who are dead or not in a world shouldn't move...
        }
    }

    @ModifyExpressionValue(method = "causeFallDamage", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;mayfly:Z"))
    private boolean fission$flyingFallDamage(boolean mayfly) {
        return mayfly && !this.flyingFallDamage.toBooleanOrElse(false); // Paper - flying fall damage
    }

    // CraftBukkit start
    @Inject(method = "startFallFlying", at = @At("HEAD"), cancellable = true)
    private void fission$toggleGlide(CallbackInfo ci) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callToggleGlideEvent(this, true).isCancelled()) {
            this.setSharedFlag(Entity.FLAG_FALL_FLYING, true);
        } else {
            // SPIGOT-5542: must toggle like below
            this.setSharedFlag(Entity.FLAG_FALL_FLYING, true);
            this.setSharedFlag(Entity.FLAG_FALL_FLYING, false);
        }
        ci.cancel();
    }
    // CraftBukkit end

    @Inject(method = "causeFoodExhaustion(F)V", at = @At("HEAD"), cancellable = true)
    private void fission$exhaustionUnknown(float amount, CallbackInfo ci) {
        this.causeFoodExhaustion(amount, org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.UNKNOWN);
        ci.cancel();
    }

    // Paper start - PlayerAttackEntityEvent
    @WrapOperation(method = {"attack", "stabAttack"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;cannotAttack(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$prePlayerAttack(Player self, Entity entity, Operation<Boolean> original) {
        boolean cannotAttack = original.call(self, entity); // Vanilla logic
        io.papermc.paper.event.player.PrePlayerAttackEntityEvent playerAttackEntityEvent = new io.papermc.paper.event.player.PrePlayerAttackEntityEvent(
            (org.bukkit.entity.Player) this.getBukkitEntity(),
            entity.getBukkitEntity(),
            !cannotAttack
        );
        return !playerAttackEntityEvent.callEvent() || cannotAttack;
    }
    // Paper end - PlayerAttackEntityEvent

    @WrapOperation(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;onAttack()V"))
    private void fission$attackOnAttack(Player self, Operation<Void> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Entity entity) {
        this.onAttack(entity); // Paper
    }

    @ModifyExpressionValue(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;canCriticalAttack(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$disableCrits(boolean crit) {
        crit = crit && !this.level().paperConfig().entities.behavior.disablePlayerCrits; // Paper - Toggleable player crits
        this.fission$criticalAttack = crit; // only evaluated when fullStrengthAttack, so it equals the vanilla criticalAttack
        return crit;
    }
    // Paper - critical damage API: the vanilla local damageSource is replaced by the critical copy right after the crit roll
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isSweepAttack(ZZZ)Z"), ordinal = 0)
    private net.minecraft.world.damagesource.DamageSource fission$criticalSource(net.minecraft.world.damagesource.DamageSource damageSource) {
        final boolean critical = this.fission$criticalAttack;
        this.fission$criticalAttack = false;
        return critical ? damageSource.critical() : damageSource; // Paper - critical damage API
    }
    @org.spongepowered.asm.mixin.Unique private boolean fission$criticalAttack;

    // Paper - damage events: the projectile deflect only happens when the (non living entity) damage event is not cancelled
    @WrapOperation(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;deflectProjectile(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$deflectAttack(Player self, Entity entity, Operation<Boolean> original, @com.llamalad7.mixinextras.sugar.Local(ordinal = 0) net.minecraft.world.damagesource.DamageSource damageSource, @com.llamalad7.mixinextras.sugar.Local(name = "magicBoost") float magicBoost) {
        return this.fission$deflect(self, entity, original, damageSource, magicBoost);
    }

    @WrapOperation(method = "stabAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;deflectProjectile(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$deflectStab(Player self, Entity entity, Operation<Boolean> original, @com.llamalad7.mixinextras.sugar.Local(ordinal = 0) net.minecraft.world.damagesource.DamageSource damageSource, @com.llamalad7.mixinextras.sugar.Local(name = "magicBoost") float magicBoost) {
        return this.fission$deflect(self, entity, original, damageSource, magicBoost);
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean fission$deflect(Player self, Entity entity, Operation<Boolean> original, net.minecraft.world.damagesource.DamageSource damageSource, float magicBoost) {
        if (entity.is(net.minecraft.tags.EntityTypeTags.REDIRECTABLE_PROJECTILE) && entity instanceof net.minecraft.world.entity.projectile.Projectile
            && org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(entity, damageSource, magicBoost, false)) {
            return false; // event cancelled: no deflect
        }
        return original.call(self, entity);
    }

    // Paper - knockback events: attack knockback carries the attacker and the ENTITY_ATTACK cause, pushes carry the pushing entity
    @WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"))
    private void fission$attackKnockback(LivingEntity target, double power, double xd, double zd, net.minecraft.world.damagesource.DamageSource source, float damage, boolean comesFromEffect, Operation<Void> original) {
        target.knockback(power, xd, zd, source, damage, comesFromEffect, (Entity) (Object) this, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK);
    }

    @WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
    private void fission$attackPush(Entity target, double x, double y, double z, Operation<Void> original) {
        target.push(x, y, z, (Entity) (Object) this); // Paper - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
    }

    // Paper - Configurable sprint interruption on attack
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"))
    private boolean fission$sprintInterruption(Player self, boolean sprinting) {
        return !this.level().paperConfig().misc.disableSprintInterruptionOnAttack;
    }

    // CraftBukkit start - Add Velocity Event
    @ModifyExpressionValue(method = "causeExtraKnockback", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;syncVelocity:Z", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private boolean fission$attackVelocityEvent(boolean syncVelocity, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Entity entity, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.world.phys.Vec3 oldMovement) {
        if (!syncVelocity) {
            return false;
        }
        org.bukkit.entity.Player player = (org.bukkit.entity.Player) entity.getBukkitEntity();
        org.bukkit.util.Vector velocity = org.bukkit.craftbukkit.util.CraftVector.toBukkit(oldMovement);
        org.bukkit.event.player.PlayerVelocityEvent event = new org.bukkit.event.player.PlayerVelocityEvent(player, velocity.clone());
        if (!event.callEvent()) {
            return false; // cancelled: keep syncVelocity set and send nothing
        }
        if (!velocity.equals(event.getVelocity())) {
            player.setVelocity(event.getVelocity());
        }
        return true;
    }
    // CraftBukkit end

    // Paper - Only apply knockback if the event is not canceled; sweep attack cause and knockback events
    @WrapOperation(method = "doSweepAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean fission$sweepHurt(LivingEntity nearby, net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource damageSource, float damage, Operation<Boolean> original) {
        nearby.lastDamageCancelled = false;
        return original.call(nearby, level, damageSource.knownCause(org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK), damage) && !nearby.lastDamageCancelled;
    }

    @WrapOperation(method = "doSweepAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V"))
    private void fission$sweepKnockback(LivingEntity nearby, double power, double xd, double zd, net.minecraft.world.damagesource.DamageSource source, float damage, Operation<Void> original) {
        nearby.knockback(power, xd, zd, source, damage, (Entity) (Object) this, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.SWEEP_ATTACK);
    }

    // Paper - Add PlayerShieldDisableEvent
    @WrapOperation(method = "blockUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/BlocksAttacks;disable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;FLnet/minecraft/world/item/ItemStack;)V"))
    private void fission$shieldDisable(net.minecraft.world.item.component.BlocksAttacks blocksAttacks, net.minecraft.server.level.ServerLevel level, LivingEntity user, float baseSeconds, net.minecraft.world.item.ItemStack blockingWith, Operation<Void> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) LivingEntity attacker) {
        blocksAttacks.disable(level, user, baseSeconds, blockingWith, attacker);
    }

    // Paper start - PlayerReadyArrowEvent
    @org.spongepowered.asm.mixin.Unique private boolean fission$anyReadyArrowCancelled;

    @Inject(method = "getProjectile", at = @At("HEAD"))
    private void fission$readyArrowReset(net.minecraft.world.item.ItemStack heldWeapon, CallbackInfoReturnable<net.minecraft.world.item.ItemStack> cir) {
        this.fission$anyReadyArrowCancelled = false;
    }

    @ModifyExpressionValue(method = "getProjectile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ProjectileWeaponItem;getSupportedHeldProjectiles()Ljava/util/function/Predicate;"))
    private java.util.function.Predicate<net.minecraft.world.item.ItemStack> fission$readyArrowHeld(java.util.function.Predicate<net.minecraft.world.item.ItemStack> predicate, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.world.item.ItemStack heldWeapon) {
        return predicate.and(item -> this.fission$tryReadyArrow(heldWeapon, item));
    }

    @ModifyExpressionValue(method = "getProjectile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ProjectileWeaponItem;getAllSupportedProjectiles()Ljava/util/function/Predicate;"))
    private java.util.function.Predicate<net.minecraft.world.item.ItemStack> fission$readyArrowAll(java.util.function.Predicate<net.minecraft.world.item.ItemStack> predicate, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.world.item.ItemStack heldWeapon) {
        return predicate.and(item -> this.fission$tryReadyArrow(heldWeapon, item));
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean fission$tryReadyArrow(net.minecraft.world.item.ItemStack bow, net.minecraft.world.item.ItemStack itemstack) {
        if (!(((Object) this) instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return true;
        final boolean notCancelled = new com.destroystokyo.paper.event.player.PlayerReadyArrowEvent(
            serverPlayer.getBukkitEntity(),
            org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(bow),
            org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemstack)
        ).callEvent();
        if (!notCancelled) this.fission$anyReadyArrowCancelled = true;
        return notCancelled;
    }

    @Inject(method = "getProjectile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z"))
    private void fission$readyArrowResync(net.minecraft.world.item.ItemStack heldWeapon, CallbackInfoReturnable<net.minecraft.world.item.ItemStack> cir) {
        if (this.fission$anyReadyArrowCancelled && !this.abilities.instabuild && ((Object) this) instanceof net.minecraft.server.level.ServerPlayer player) {
            this.resyncUsingItem(player); // Paper - resync if no item matched the Predicate
        }
    }
    // Paper end - PlayerReadyArrowEvent

    @WrapOperation(method = {"attack", "stabAttack"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private void fission$attackExhaustion(Player self, float amount, Operation<Void> original) {
        self.causeFoodExhaustion(self.level().spigotConfig.combatExhaustion, org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.ATTACK); // CraftBukkit - EntityExhaustionEvent // Spigot - Change to use configurable value
    }

    // Paper start - send while respecting visibility
    private static void sendSoundEffect(Player fromEntity, double x, double y, double z, net.minecraft.sounds.SoundEvent soundEffect, net.minecraft.sounds.SoundSource soundCategory, float volume, float pitch) {
        fromEntity.level().playSound(fromEntity, x, y, z, soundEffect, soundCategory, volume, pitch); // This will not send the effect to the entity itself
        if (fromEntity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(soundEffect), soundCategory, x, y, z, volume, pitch, fromEntity.getRandom().nextLong()));
        }
    }

    @WrapOperation(method = {"playServerSideSound", "giveExperienceLevels"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void fission$sendSound(Level level, @Nullable Entity except, double x, double y, double z, net.minecraft.sounds.SoundEvent sound, net.minecraft.sounds.SoundSource source, float volume, float pitch, Operation<Void> original) {
        sendSoundEffect((Player) (Object) this, x, y, z, sound, source, volume, pitch);
    }
    // Paper end - send while respecting visibility

    @Inject(method = "onAttack()V", at = @At("HEAD"), cancellable = true)
    private void fission$onAttackNull(CallbackInfo ci) {
        this.onAttack(null);
        ci.cancel();
    }

    // Paper start
    @Override
    public void onAttack(@Nullable Entity entity) {
        if (entity != null) {
            if (new com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent((org.bukkit.entity.Player) this.getBukkitEntity(), entity.getBukkitEntity(), this.getAttackStrengthScale(0F)).callEvent()) {
                this.resetOnlyAttackStrengthTicker();
            }
        } else {
            this.resetOnlyAttackStrengthTicker();
        }
        super.onAttack(entity);
    // Paper end
    }
}
