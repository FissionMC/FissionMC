package net.fissionmc.backend.mixin.world.entity.animal;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link Animal} (paper-server/patches/sources/net/minecraft/world/entity/animal/Animal.java.patch).
 */
@Mixin(Animal.class)
public abstract class AnimalMixin {
    @Shadow private int inLove;
    @Shadow private @Nullable EntityReference<ServerPlayer> loveCause;

    @Shadow public abstract @Nullable ServerPlayer getLoveCause();
    @Shadow public abstract void resetLove();

    private @Nullable ItemStack breedItem; // CraftBukkit - Add breedItem variable

    public void setInLove(final @Nullable Player player, final @Nullable ItemStack breedItemCopy) {
        Animal self = (Animal) (Object) this;
        if (breedItemCopy != null) this.breedItem = breedItemCopy;
        // Paper end - Fix EntityBreedEvent copying
        // CraftBukkit start
        org.bukkit.event.entity.EntityEnterLoveModeEvent entityEnterLoveModeEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityEnterLoveModeEvent(player, self, 600);
        if (entityEnterLoveModeEvent.isCancelled()) {
            this.breedItem = null; // Paper - Fix EntityBreedEvent copying; clear if cancelled
            return;
        }
        this.inLove = entityEnterLoveModeEvent.getTicksInLove();
        // CraftBukkit end
        if (player instanceof ServerPlayer serverPlayer) {
            this.loveCause = EntityReference.of(serverPlayer);
        }

        self.level().broadcastEntityEvent(self, EntityEvent.IN_LOVE_HEARTS);
    }

    public void finalizeSpawnChildFromBreeding(final ServerLevel level, final Animal partner, final @Nullable AgeableMob offspring, final int experience) {
        Animal self = (Animal) (Object) this;
        // CraftBukkit end - Call EntityBreedEvent
        // Paper start - Call EntityBreedEvent
        ServerPlayer player = this.getLoveCause();
        if (player == null) player = partner.getLoveCause();
        if (player != null) {
            // Paper end - Call EntityBreedEvent
            player.awardStat(Stats.ANIMALS_BRED);
            CriteriaTriggers.BRED_ANIMALS.trigger(player, self, partner, offspring);
        } // Paper - Call EntityBreedEvent
        self.setAge(6000);
        partner.setAge(6000);
        this.resetLove();
        partner.resetLove();
        level.broadcastEntityEvent(self, EntityEvent.IN_LOVE_HEARTS);
        if (experience > 0 && level.getGameRules().get(GameRules.MOB_DROPS)) { // Paper - Call EntityBreedEvent
            level.addFreshEntity(ExperienceOrb.create(level, self.position(), Vec3.ZERO, experience, org.bukkit.entity.ExperienceOrb.SpawnReason.BREED, player, offspring)); // Paper - Call EntityBreedEvent, add spawn context // FissionMC: factory for Paper's constructor
        }
    }

    // Done: setInLove / finalizeSpawnChildFromBreeding delegation, EntityBreedEvent, BREEDING spawn reason, breed item copy. TODO(behaviour): actuallyHurt(event) overload.
    private @Nullable ItemStack fission$breedCopy;
    private int fission$breedExperience = -1;

    @Inject(method = "setInLove(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"), cancellable = true)
    private void fission$setInLoveDelegate(final Player player, final CallbackInfo ci) {
        this.setInLove(player, null);
        ci.cancel();
    }

    @Inject(method = "finalizeSpawnChildFromBreeding(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V", at = @At("HEAD"), cancellable = true)
    private void fission$finalizeDelegate(final ServerLevel level, final Animal partner, final @Nullable AgeableMob offspring, final CallbackInfo ci) {
        this.finalizeSpawnChildFromBreeding(level, partner, offspring, ((Animal) (Object) this).getRandom().nextInt(7) + 1);
        ci.cancel();
    }

    // mobInteract: remember a copy of the breeding item before it is consumed
    @Inject(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Animal;usePlayerItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)V", ordinal = 0))
    private void fission$breedCopy(final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir) {
        this.fission$breedCopy = player.getItemInHand(hand).copy(); // Paper - Fix EntityBreedEvent copying
    }

    @WrapOperation(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Animal;setInLove(Lnet/minecraft/world/entity/player/Player;)V"))
    private void fission$setInLoveWithItem(final Animal self, final Player player, final Operation<Void> original) {
        final ItemStack copy = this.fission$breedCopy;
        this.fission$breedCopy = null;
        self.setInLove(player, copy); // Paper - Fix EntityBreedEvent copying
    }

    // spawnChildFromBreeding: EntityBreedEvent (cancel aborts before finalize / spawn)
    @Inject(method = "spawnChildFromBreeding", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Animal;finalizeSpawnChildFromBreeding(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V", ordinal = 0), cancellable = true)
    private void fission$breedEvent(final ServerLevel level, final Animal partner, final CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local AgeableMob offspring) {
        final Animal self = (Animal) (Object) this;
        ServerPlayer breeder = java.util.Optional.ofNullable(this.getLoveCause()).or(() -> java.util.Optional.ofNullable(partner.getLoveCause())).orElse(null);
        int experience = self.getRandom().nextInt(7) + 1;
        org.bukkit.event.entity.EntityBreedEvent entityBreedEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityBreedEvent(offspring, self, partner, breeder, this.breedItem, experience);
        if (entityBreedEvent.isCancelled()) {
            this.resetLove();
            partner.resetLove();
            ci.cancel();
            return;
        }
        this.fission$breedExperience = entityBreedEvent.getExperience();
    }

    @WrapOperation(method = "spawnChildFromBreeding", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Animal;finalizeSpawnChildFromBreeding(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V"))
    private void fission$finalizeWithExperience(final Animal self, final ServerLevel level, final Animal partner, final AgeableMob offspring, final Operation<Void> original) {
        int experience = this.fission$breedExperience;
        this.fission$breedExperience = -1;
        self.finalizeSpawnChildFromBreeding(level, partner, offspring, experience >= 0 ? experience : self.getRandom().nextInt(7) + 1);
    }

    @WrapOperation(method = "spawnChildFromBreeding", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"))
    private void fission$spawnReason(final ServerLevel level, final net.minecraft.world.entity.Entity offspring, final Operation<Void> original) {
        level.addFreshEntityWithPassengers(offspring, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BREEDING);
    }
}
