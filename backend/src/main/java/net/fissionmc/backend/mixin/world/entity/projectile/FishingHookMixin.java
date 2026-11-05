package net.fissionmc.backend.mixin.world.entity.projectile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Collections;
import java.util.List;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/projectile/FishingHook.java.patch */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin extends Projectile {
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_BITING;
    @Shadow private int timeUntilLured;
    @Shadow private int timeUntilHooked;
    @Shadow private int nibble;
    @Shadow private float fishAngle;
    @Shadow private @Nullable Entity hookedIn;
    @Shadow @Final private int luck;
    @Shadow @Final private int lureSpeed;

    @Shadow public abstract @Nullable Player getPlayerOwner();
    @Shadow private boolean shouldStopFishing(Player owner) { throw new AssertionError(); }
    @Shadow protected abstract void pullEntity(Entity entity);
    @Shadow private void updateOwnerInfo(@Nullable FishingHook hook) { throw new AssertionError(); }

    // CraftBukkit start - Extra variables to enable modification of fishing wait time, values are minecraft defaults
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private int minWaitTime = 100;
    private int maxWaitTime = 600;
    private int minLureTime = 20;
    private int maxLureTime = 80;
    private float minLureAngle = 0.0F;
    private float maxLureAngle = 360.0F;
    private boolean applyLure = true;
    private boolean rainInfluenced = true;
    private boolean skyInfluenced = true;
    // CraftBukkit end

    protected FishingHookMixin(final EntityType<? extends Projectile> type, final Level level) {
        super(type, level);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;II)V", at = @At("TAIL"))
    private void fission$fishingTimeRange(EntityType<? extends FishingHook> type, Level level, int luck, int lureSpeed, CallbackInfo ci) {
        // Paper start - Configurable fishing time ranges
        this.minWaitTime = level.paperConfig().fishingTimeRange.minimum;
        this.maxWaitTime = level.paperConfig().fishingTimeRange.maximum;
        // Paper end - Configurable fishing time ranges
    }

    @Redirect(method = {"tick", "shouldStopFishing"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FishingHook;discard()V"))
    private void fission$discardDespawn(FishingHook instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    // Paper start - Add FishHookStateChangeEvent
    @WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/projectile/FishingHook;currentState:Lnet/minecraft/world/entity/projectile/FishingHook$FishHookState;", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void fission$stateChange(FishingHook instance, FishingHook.FishHookState state, Operation<Void> original) {
        final org.bukkit.entity.FishHook.HookState bukkitState = switch (state) {
            case HOOKED_IN_ENTITY -> org.bukkit.entity.FishHook.HookState.HOOKED_ENTITY; // #HOOKED_ENTITY
            case BOBBING -> org.bukkit.entity.FishHook.HookState.BOBBING; // #BOBBING
            case FLYING -> org.bukkit.entity.FishHook.HookState.UNHOOKED; // #UNHOOKED
        };
        new io.papermc.paper.event.entity.FishHookStateChangeEvent((org.bukkit.entity.FishHook) this.getBukkitEntity(), bukkitState).callEvent();
        original.call(instance, state);
    }
    // Paper end - Add FishHookStateChangeEvent

    @Redirect(method = "checkCollision", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FishingHook;hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;"))
    private ProjectileDeflection fission$preHit(FishingHook instance, HitResult hitResult) {
        return instance.preHitTargetOrDeflectSelf(hitResult);
    }

    // FissionMC: replaced body (Paper's catchingFish: CraftBukkit wait/lure variables, PlayerFishEvent FAILED_ATTEMPT/BITE/LURED)
    @Inject(method = "catchingFish", at = @At("HEAD"), cancellable = true)
    private void fission$catchingFish(BlockPos blockPos, CallbackInfo ci) {
        ci.cancel();
        ServerLevel serverLevel = (ServerLevel) this.level();
        int fishingSpeed = 1;
        BlockPos above = blockPos.above();
        if (this.rainInfluenced && this.random.nextFloat() < 0.25F && this.level().isRainingAt(above)) { // CraftBukkit
            fishingSpeed++;
        }

        if (this.skyInfluenced && this.random.nextFloat() < 0.5F && !this.level().canSeeSky(above)) { // CraftBukkit
            fishingSpeed--;
        }

        if (this.nibble > 0) {
            this.nibble--;
            if (this.nibble <= 0) {
                this.timeUntilLured = 0;
                this.timeUntilHooked = 0;
                this.getEntityData().set(DATA_BITING, false);
                // CraftBukkit start
                org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) this.getPlayerOwner().getBukkitEntity(), null, (org.bukkit.entity.FishHook) this.getBukkitEntity(), org.bukkit.event.player.PlayerFishEvent.State.FAILED_ATTEMPT);
                playerFishEvent.callEvent();
                // CraftBukkit end
            }
        } else if (this.timeUntilHooked > 0) {
            this.timeUntilHooked -= fishingSpeed;
            if (this.timeUntilHooked > 0) {
                this.fishAngle = this.fishAngle + (float) this.random.triangle(0.0, 9.188);
                float angle = this.fishAngle * (float) (Math.PI / 180.0);
                float angleSin = Mth.sin(angle);
                float angleCos = Mth.cos(angle);
                double fishX = this.getX() + angleSin * this.timeUntilHooked * 0.1F;
                double fishY = Mth.floor(this.getY()) + 1.0F;
                double fishZ = this.getZ() + angleCos * this.timeUntilHooked * 0.1F;
                BlockState splashBlockState = serverLevel.getBlockState(BlockPos.containing(fishX, fishY - 1.0, fishZ));
                if (splashBlockState.is(Blocks.WATER)) {
                    if (this.random.nextFloat() < 0.15F) {
                        serverLevel.sendParticles(ParticleTypes.BUBBLE, fishX, fishY - 0.1F, fishZ, 1, angleSin, 0.1, angleCos, 0.0);
                    }

                    float particleXMovement = angleSin * 0.04F;
                    float particleZMovement = angleCos * 0.04F;
                    serverLevel.sendParticles(ParticleTypes.FISHING, fishX, fishY, fishZ, 0, particleZMovement, 0.01, -particleXMovement, 1.0);
                    serverLevel.sendParticles(ParticleTypes.FISHING, fishX, fishY, fishZ, 0, -particleZMovement, 0.01, particleXMovement, 1.0);
                }
            } else {
                // CraftBukkit start
                org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) this.getPlayerOwner().getBukkitEntity(), null, (org.bukkit.entity.FishHook) this.getBukkitEntity(), org.bukkit.event.player.PlayerFishEvent.State.BITE);
                if (!playerFishEvent.callEvent()) {
                    return;
                }
                // CraftBukkit end
                this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25F, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
                double y = this.getY() + 0.5;
                serverLevel.sendParticles(
                    ParticleTypes.BUBBLE, this.getX(), y, this.getZ(), (int) (1.0F + this.getBbWidth() * 20.0F), this.getBbWidth(), 0.0, this.getBbWidth(), 0.2F
                );
                serverLevel.sendParticles(
                    ParticleTypes.FISHING, this.getX(), y, this.getZ(), (int) (1.0F + this.getBbWidth() * 20.0F), this.getBbWidth(), 0.0, this.getBbWidth(), 0.2F
                );
                this.nibble = Mth.nextInt(this.random, 20, 40);
                this.getEntityData().set(DATA_BITING, true);
            }
        } else if (this.timeUntilLured > 0) {
            this.timeUntilLured -= fishingSpeed;
            float teaseChance = 0.15F;
            if (this.timeUntilLured < 20) {
                teaseChance += (20 - this.timeUntilLured) * 0.05F;
            } else if (this.timeUntilLured < 40) {
                teaseChance += (40 - this.timeUntilLured) * 0.02F;
            } else if (this.timeUntilLured < 60) {
                teaseChance += (60 - this.timeUntilLured) * 0.01F;
            }

            if (this.random.nextFloat() < teaseChance) {
                float angle = Mth.nextFloat(this.random, 0.0F, 360.0F) * (float) (Math.PI / 180.0);
                float dist = Mth.nextFloat(this.random, 25.0F, 60.0F);
                double fishX = this.getX() + Mth.sin(angle) * dist * 0.1;
                double fishY = Mth.floor(this.getY()) + 1.0F;
                double fishZ = this.getZ() + Mth.cos(angle) * dist * 0.1;
                BlockState splashBlockState = serverLevel.getBlockState(BlockPos.containing(fishX, fishY - 1.0, fishZ));
                if (splashBlockState.is(Blocks.WATER)) {
                    serverLevel.sendParticles(ParticleTypes.SPLASH, fishX, fishY, fishZ, 2 + this.random.nextInt(2), 0.1F, 0.0, 0.1F, 0.0);
                }
            }

            if (this.timeUntilLured <= 0) {
                // CraftBukkit start - logic to modify fishing wait time, lure time, and lure angle
                this.fishAngle = Mth.nextFloat(this.random, this.minLureAngle, this.maxLureAngle);
                this.timeUntilHooked = Mth.nextInt(this.random, this.minLureTime, this.maxLureTime);
                // CraftBukkit end
                // Paper start - Add missing fishing event state
                if (this.getPlayerOwner() != null) {
                    org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) this.getPlayerOwner().getBukkitEntity(), null, (org.bukkit.entity.FishHook) this.getBukkitEntity(), org.bukkit.event.player.PlayerFishEvent.State.LURED);
                    if (!playerFishEvent.callEvent()) {
                        this.timeUntilHooked = 0;
                        return;
                    }
                }
                // Paper end - Add missing fishing event state
            }
        } else {
            this.resetTimeUntilLured(); // Paper - more projectile api - extract time until lured reset logic
        }
    }

    // Paper start - more projectile api - extract time until lured reset logic
    public void resetTimeUntilLured() {
        this.timeUntilLured = Mth.nextInt(this.random, this.minWaitTime, this.maxWaitTime);
        this.timeUntilLured -= (this.applyLure) ? (this.lureSpeed >= this.maxWaitTime ? this.timeUntilLured - 1 : this.lureSpeed ) : 0; // Paper - Fix Lure infinite loop
    }
    // Paper end - more projectile api - extract time until lured reset logic

    // Paper start - Add hand parameter to PlayerFishEvent
    @Inject(method = "retrieve(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
    private void fission$retrieveMainHand(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.retrieve(rod, InteractionHand.MAIN_HAND));
    }

    public int retrieve(final ItemStack rod, final InteractionHand hand) {
        // Paper end - Add hand parameter to PlayerFishEvent
        Player owner = this.getPlayerOwner();
        if (!this.level().isClientSide() && owner != null && !this.shouldStopFishing(owner)) {
            int dmg = 0;
            if (this.hookedIn != null) {
                // CraftBukkit start
                org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(), this.hookedIn.getBukkitEntity(), (org.bukkit.entity.FishHook) this.getBukkitEntity(), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand), org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_ENTITY); // Paper - Add hand parameter to PlayerFishEvent
                if (!playerFishEvent.callEvent()) {
                    return 0;
                }
                if (this.hookedIn != null) { // Paper - re-check to see if there is a hooked entity
                // CraftBukkit end
                this.pullEntity(this.hookedIn);
                CriteriaTriggers.FISHING_ROD_HOOKED.trigger((ServerPlayer) owner, rod, (FishingHook) (Object) this, Collections.emptyList());
                this.level().broadcastEntityEvent(this, net.minecraft.world.entity.EntityEvent.FISHING_ROD_REEL_IN);
                dmg = this.hookedIn instanceof ItemEntity ? 3 : 5;
                } // Paper - re-check to see if there is a hooked entity
            } else if (this.nibble > 0) {
                LootParams params = new LootParams.Builder((ServerLevel) this.level())
                    .withParameter(LootContextParams.ORIGIN, this.position())
                    .withParameter(LootContextParams.TOOL, rod)
                    .withParameter(LootContextParams.THIS_ENTITY, this)
                    .withLuck(this.luck + owner.getLuck())
                    .create(LootContextParamSets.FISHING);
                LootTable lootTable = this.level().getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
                List<ItemStack> items = lootTable.getRandomItems(params);
                CriteriaTriggers.FISHING_ROD_HOOKED.trigger((ServerPlayer) owner, rod, (FishingHook) (Object) this, items);

                for (ItemStack itemStack : items) {
                    ItemEntity entity = new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), itemStack);
                    // CraftBukkit start
                    org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(), entity.getBukkitEntity(), (org.bukkit.entity.FishHook) this.getBukkitEntity(), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand), org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH); // Paper - itemEntity may be null // Paper - Add hand parameter to PlayerFishEvent
                    playerFishEvent.setExpToDrop(this.random.nextInt(6) + 1);
                    if (!playerFishEvent.callEvent()) {
                        return 0;
                    }
                    // CraftBukkit end
                    double xa = owner.getX() - this.getX();
                    double ya = owner.getY() - this.getY();
                    double za = owner.getZ() - this.getZ();
                    double speed = 0.1;
                    entity.setDeltaMovement(xa * 0.1, ya * 0.1 + Math.sqrt(Math.sqrt(xa * xa + ya * ya + za * za)) * 0.08, za * 0.1);
                    this.level().addFreshEntity(entity);
                    if (playerFishEvent.getExpToDrop() > 0) { // CraftBukkit - custom exp
                        owner.level()
                            .addFreshEntity(
                                ExperienceOrb.create( // FissionMC: static factory for Paper-added constructor
                                    owner.level(), new net.minecraft.world.phys.Vec3(owner.getX(), owner.getY() + 0.5, owner.getZ() + 0.5), net.minecraft.world.phys.Vec3.ZERO, playerFishEvent.getExpToDrop(), org.bukkit.entity.ExperienceOrb.SpawnReason.FISHING, this.getPlayerOwner(), this // Paper
                                )
                            );
                    }
                    if (itemStack.is(ItemTags.FISHES)) {
                        owner.awardStat(Stats.FISH_CAUGHT, 1);
                    }
                }

                dmg = 1;
            }

            if (this.onGround()) {
                // CraftBukkit start
                org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(), null, (org.bukkit.entity.FishHook) this.getBukkitEntity(), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand), org.bukkit.event.player.PlayerFishEvent.State.IN_GROUND); // Paper - Add hand parameter to PlayerFishEvent
                if (!playerFishEvent.callEvent()) {
                    return 0;
                }
                // CraftBukkit end
                dmg = 2;
            }
            // CraftBukkit start
            if (dmg == 0) {
                org.bukkit.event.player.PlayerFishEvent playerFishEvent = new org.bukkit.event.player.PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(), null, (org.bukkit.entity.FishHook) this.getBukkitEntity(), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand), org.bukkit.event.player.PlayerFishEvent.State.REEL_IN); // Paper - Add hand parameter to PlayerFishEvent
                if (!playerFishEvent.callEvent()) {
                    return 0;
                }
            }
            // CraftBukkit end

            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
            return dmg;
        } else {
            return 0;
        }
    }

    // CraftBukkit start - add Bukkit remove cause
    @Inject(method = "remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("HEAD"), cancellable = true)
    private void fission$removeDelegate(Entity.RemovalReason reason, CallbackInfo ci) {
        ci.cancel();
        this.remove(reason, null);
    }

    @Override
    public void remove(final Entity.RemovalReason reason, final org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) {
        this.updateOwnerInfo(null);
        super.remove(reason, cause);
    }
    // CraftBukkit end
}
