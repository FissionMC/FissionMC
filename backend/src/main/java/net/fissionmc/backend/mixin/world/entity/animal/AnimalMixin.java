package net.fissionmc.backend.mixin.world.entity.animal;

import net.minecraft.advancements.CriteriaTriggers;
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

    private @Nullable ItemStack breedItem; // CraftBukkit - Add breedItem variable // TODO(behaviour): spawnChildFromBreeding fires EntityBreedEvent with it; mobInteract passes the copy

    // TODO(behaviour): vanilla setInLove(player) should delegate here with null
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

    // TODO(behaviour): vanilla finalizeSpawnChildFromBreeding(level, partner, offspring) should delegate here with random 1..7 experience
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
}
