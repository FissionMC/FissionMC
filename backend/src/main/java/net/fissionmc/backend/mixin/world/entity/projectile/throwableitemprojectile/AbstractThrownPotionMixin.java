package net.fissionmc.backend.mixin.world.entity.projectile.throwableitemprojectile;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/projectile/throwableitemprojectile/AbstractThrownPotion.java.patch */
@Mixin(AbstractThrownPotion.class)
public abstract class AbstractThrownPotionMixin {
    @Shadow private void affectEntitiesAround(final ServerLevel level, final PotionContents potion) {}
    @Shadow protected abstract void onHitAsPotion(ServerLevel level, ItemStack potionItem, HitResult hitResult);

    // Paper start - More projectile API
    public void splash(final @org.jspecify.annotations.Nullable HitResult hitResult) { // Paper
        // TODO(behaviour): Paper's version calls the WaterBottleSplashEvent/PotionSplashEvent variants
        //  (affectEntitiesAround/onHitAsPotion return booleans, fires only shown when events pass), moves
        //  extinguishFires after the event, and vanilla onHit should delegate here. This body is the vanilla onHit tail.
        AbstractThrownPotion self = (AbstractThrownPotion) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            ItemStack potionItemStack = self.getItem();
            PotionContents potion = potionItemStack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            this.affectEntitiesAround(level, potion);
            if (potion.hasEffects()) { // TODO(behaviour): CraftBukkit calls this even without effects (Paper onHitAsPotion overrides handle that)
                this.onHitAsPotion(level, potionItemStack, hitResult);
            }

            if (potion.potion().isPresent() && potion.potion().get().value().hasInstantEffects()) {
                level.levelEvent(LevelEvent.PARTICLES_INSTANT_POTION_SPLASH, self.blockPosition(), potion.getColor());
                if (!self.isSilent()) {
                    level.levelEvent(LevelEvent.SOUND_INSTANT_POTION_SPLASH, self.blockPosition(), 0);
                }
            } else {
                level.levelEvent(LevelEvent.PARTICLES_SPELL_POTION_SPLASH, self.blockPosition(), potion.getColor());
                if (!self.isSilent()) {
                    level.levelEvent(LevelEvent.SOUND_SPELL_POTION_SPLASH, self.blockPosition(), 0);
                }
            }

            ((Entity) self).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
        }
    }
    // Paper end - More projectile API
}
