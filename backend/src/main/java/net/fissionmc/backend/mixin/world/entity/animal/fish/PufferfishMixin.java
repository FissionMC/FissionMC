package net.fissionmc.backend.mixin.world.entity.animal.fish;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/entity/animal/fish/Pufferfish.java.patch
 * done: PufferFishStateChangeEvent in tick (a cancelled change plays no sound, keeps the state and does not advance the inflate/deflate counter).
 * Not ported: EntityPotionEffectEvent causes of the sting effects.
 */
@Mixin(Pufferfish.class)
public abstract class PufferfishMixin {
    @Shadow private int inflateCounter;
    @Shadow private int deflateTimer;
    @Unique private boolean paper$stateCancelled;

    // every state change in tick() is makeSound(BLOW_UP|BLOW_OUT) followed by setPuffState(next): the event fires at the sound
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/fish/Pufferfish;makeSound(Lnet/minecraft/sounds/SoundEvent;)V"))
    private void paper$stateEvent(final Pufferfish self, final SoundEvent sound, final Operation<Void> original) {
        final boolean inflating = sound == SoundEvents.PUFFER_FISH_BLOW_UP;
        final int state = self.getPuffState();
        final int next = inflating ? (state == 0 ? Pufferfish.STATE_MID : Pufferfish.STATE_FULL) : (state == Pufferfish.STATE_FULL ? Pufferfish.STATE_MID : Pufferfish.STATE_SMALL);
        if (new io.papermc.paper.event.entity.PufferFishStateChangeEvent((org.bukkit.entity.PufferFish) self.getBukkitEntity(), next).callEvent()) { // Paper - Add PufferFishStateChangeEvent
            original.call(self, sound);
        } else {
            this.paper$stateCancelled = true;
            if (inflating) this.inflateCounter--; else this.deflateTimer--; // vanilla increments right after: net zero, like Paper's inflate/deflate = false
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/fish/Pufferfish;setPuffState(I)V"))
    private void paper$stateCancelled(final Pufferfish self, final int state, final Operation<Void> original) {
        if (this.paper$stateCancelled) {
            this.paper$stateCancelled = false;
            return;
        }
        original.call(self, state);
    }
}
