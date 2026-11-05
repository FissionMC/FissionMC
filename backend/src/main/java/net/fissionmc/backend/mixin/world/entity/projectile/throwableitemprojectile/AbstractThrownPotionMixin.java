package net.fissionmc.backend.mixin.world.entity.projectile.throwableitemprojectile;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.PotionTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/projectile/throwableitemprojectile/AbstractThrownPotion.java.patch */
@Mixin(AbstractThrownPotion.class)
public abstract class AbstractThrownPotionMixin extends ThrowableItemProjectile {
    @Shadow protected abstract void onHitAsPotion(ServerLevel level, ItemStack potionItem, HitResult hitResult);

    private static final Predicate<LivingEntity> APPLY_WATER_GET_ENTITIES_PREDICATE = AbstractThrownPotion.WATER_SENSITIVE_OR_ON_FIRE.or(Axolotl.class::isInstance); // Paper - Fix potions splash events

    protected AbstractThrownPotionMixin(final EntityType<? extends ThrowableItemProjectile> type, final Level level) {
        super(type, level);
    }

    // Paper - move after PotionSplashEvent call to prevent putting out fires for cancelled events
    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void fission$onHitBlock(BlockHitResult hitResult, CallbackInfo ci) {
        ci.cancel();
        super.onHitBlock(hitResult);
    }

    protected void fission$extinguishFires(final BlockHitResult hitResult) { // Paper - move after PotionSplashEvent call to prevent putting out fires for cancelled events
        if (!this.level().isClientSide()) {
            ItemStack potionItemStack = this.getItem();
            Direction hitDirection = hitResult.getDirection();
            BlockPos blockHitPos = hitResult.getBlockPos();
            BlockPos blockEffectPos = blockHitPos.relative(hitDirection);
            PotionContents potion = potionItemStack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (potion.is(PotionTags.DOUSES_FIRE)) {
                this.douseFire(blockEffectPos);
                this.douseFire(blockEffectPos.relative(hitDirection.getOpposite()));

                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    this.douseFire(blockEffectPos.relative(direction));
                }
            }
        }
    }

    @Shadow private void douseFire(BlockPos pos) { throw new AssertionError(); }

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void fission$onHit(HitResult hitResult, CallbackInfo ci) {
        ci.cancel();
        super.onHit(hitResult);
        // Paper start - More projectile API
        this.splash(hitResult);
    }

    public void splash(final @Nullable HitResult hitResult) { // Paper
        // Paper end - More projectile API
        if (this.level() instanceof ServerLevel level) {
            ItemStack potionItemStack = this.getItem();
            PotionContents potion = potionItemStack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            boolean showEffects = this.fission$affectEntitiesAround(level, potion, hitResult); // Paper - pass HitResult / Fix potions splash events
            if (true || potion.hasEffects()) { // CraftBukkit - Call event even if no effects to apply
                showEffects |= this.fission$onHitAsPotion(level, potionItemStack, hitResult); // Paper - pass HitResult
            }

            if (showEffects) { // Paper - Fix potions splash events
            if (potion.potion().isPresent() && potion.potion().get().value().hasInstantEffects()) {
                level.levelEvent(LevelEvent.PARTICLES_INSTANT_POTION_SPLASH, this.blockPosition(), potion.getColor());
                if (!this.isSilent()) {
                    level.levelEvent(LevelEvent.SOUND_INSTANT_POTION_SPLASH, this.blockPosition(), 0);
                }
            } else {
                level.levelEvent(LevelEvent.PARTICLES_SPELL_POTION_SPLASH, this.blockPosition(), potion.getColor());
                if (!this.isSilent()) {
                    level.levelEvent(LevelEvent.SOUND_SPELL_POTION_SPLASH, this.blockPosition(), 0);
                }
            }
            } // Paper - Fix potions splash events

            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
        }
    }

    // FissionMC: Paper changes the abstract onHitAsPotion to return boolean (and accept a null HitResult);
    // ThrownSplashPotion/ThrownLingeringPotion mixins override this with Paper's bodies.
    public boolean fission$onHitAsPotion(ServerLevel level, ItemStack potionItem, @Nullable HitResult hitResult) {
        this.onHitAsPotion(level, potionItem, hitResult);
        return true;
    }

    private boolean fission$affectEntitiesAround(final ServerLevel level, final PotionContents potion, final @Nullable HitResult hitResult) { // Paper - PotionSplashEvents
        boolean hurtsWaterSensitiveEntities = potion.is(PotionTags.HURTS_WATER_SENSITIVE_ENTITIES);
        boolean extinguishesEntities = potion.is(PotionTags.EXTINGUISHES_ENTITIES);
        AABB aabb = this.getBoundingBox().inflate(4.0, 2.0, 4.0);
        // Paper start - Fix potions splash events
        java.util.Map<org.bukkit.entity.LivingEntity, Double> affected = new java.util.HashMap<>();
        java.util.Set<org.bukkit.entity.LivingEntity> rehydrate = new java.util.HashSet<>();
        java.util.Set<org.bukkit.entity.LivingEntity> extinguish = new java.util.HashSet<>();
        // Paper end - Fix potions splash events
        // Paper start - only compute entities once
        final boolean rehydratesAxolotls = potion.is(PotionTags.REHYDRATES_AXOLOTLS);
        if (hurtsWaterSensitiveEntities || extinguishesEntities || rehydratesAxolotls) {
            for (LivingEntity entity : this.level().getEntitiesOfClass(LivingEntity.class, aabb, APPLY_WATER_GET_ENTITIES_PREDICATE)) {
                if (rehydratesAxolotls && entity instanceof final Axolotl axolot) {
                    rehydrate.add(axolot.getBukkitEntity()); // Paper - PotionSplashEvents
                }
                // Paper end - only compute entities once
                double dist = this.distanceToSqr(entity);
                if (dist < 16.0) {
                    if (hurtsWaterSensitiveEntities && entity.isSensitiveToWater()) {
                        affected.put(entity.getBukkitEntity(), 1.0); // Paper - PotionSplashEvents
                    }

                    if (extinguishesEntities && entity.isOnFire() && entity.isAlive()) {
                        extinguish.add(entity.getBukkitEntity()); // Paper - PotionSplashEvents
                    }
                }
            }
        }

        // Paper - only compute entities once - move up
        // Paper start - Fix potions splash events
        io.papermc.paper.event.entity.WaterBottleSplashEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callWaterBottleSplashEvent(
            (AbstractThrownPotion) (Object) this, hitResult, affected, rehydrate, extinguish
        );
        if (!event.isCancelled()) {
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                this.fission$extinguishFires((BlockHitResult) hitResult);
            }

            for (org.bukkit.entity.LivingEntity affectedEntity : event.getToDamage()) {
                ((org.bukkit.craftbukkit.entity.CraftLivingEntity) affectedEntity).getHandle().hurtServer(level, this.damageSources().indirectMagic(this, this.getOwner()), 1.0F);
            }
            for (org.bukkit.entity.LivingEntity toExtinguish : event.getToExtinguish()) {
                ((org.bukkit.craftbukkit.entity.CraftLivingEntity) toExtinguish).getHandle().extinguishFire();
            }
            for (org.bukkit.entity.LivingEntity toRehydrate : event.getToRehydrate()) {
                if (((org.bukkit.craftbukkit.entity.CraftLivingEntity) toRehydrate).getHandle() instanceof Axolotl axolotl) {
                    axolotl.rehydrate();
                }
            }
        }
        return !event.isCancelled();
        // Paper end - Fix potions splash events
    }

    @Inject(method = "douseFire", at = @At("HEAD"), cancellable = true)
    private void fission$douseFire(BlockPos pos, CallbackInfo ci) {
        ci.cancel();
        BlockState blockState = this.level().getBlockState(pos);
        if (blockState.is(BlockTags.FIRE)) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this, pos, blockState.getFluidState().createLegacyBlock())) { // Paper - fix wrong block state
            this.level().destroyBlock(pos, false, this);
            } // CraftBukkit
        } else if (AbstractCandleBlock.isLit(blockState)) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this, pos, blockState.setValue(AbstractCandleBlock.LIT, false))) { // CraftBukkit
            AbstractCandleBlock.extinguish(null, blockState, this.level(), pos);
            } // CraftBukkit
        } else if (CampfireBlock.isLitCampfire(blockState)) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this, pos, blockState.setValue(CampfireBlock.LIT, false))) { // CraftBukkit
            this.level().levelEvent(null, LevelEvent.SOUND_EXTINGUISH_FIRE, pos, 0);
            CampfireBlock.douse(this.getOwner(), this.level(), pos, blockState);
            this.level().setBlockAndUpdate(pos, blockState.setValue(CampfireBlock.LIT, false));
            } // CraftBukkit
        }
    }
}
