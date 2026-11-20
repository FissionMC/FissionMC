package net.fissionmc.backend.mixin.world.entity.animal.frog;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fissionmc.backend.blocks.PaperContext;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.world.entity.animal.frog.Tadpole;

/**
 * Paper: sources/net/minecraft/world/entity/animal/frog/Tadpole.java.patch
 * done: transform and spawn reasons of the conversion, age reset when cancelled.
 */
@Mixin(Tadpole.class)
public abstract class TadpoleMixin {
    @Shadow public abstract void setAge(int age);

    @WrapOperation(method = "ageUp()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/frog/Tadpole;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    private Mob paper$convertReasons(final Tadpole self, final EntityType<?> type, final ConversionParams params, final ConversionParams.AfterConversion<?> afterConversion, final Operation<Mob> original) {
        final Mob converted = PaperContext.convert(self, type, params, afterConversion, EntityTransformEvent.TransformReason.METAMORPHOSIS, CreatureSpawnEvent.SpawnReason.METAMORPHOSIS); // CraftBukkit
        if (converted == null) {
            this.setAge(0); // Sets the age to 0 for avoid a loop if the event is canceled
        }
        return converted;
    }

    @Shadow @Final private static EntityDataAccessor<Boolean> AGE_LOCKED;
    @Shadow public abstract boolean isAgeLocked();

    // Paper start - call PlayerToggleEntityAgeLockEvent
    @Inject(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/AgeableMob;setAgeLocked(Lnet/minecraft/world/entity/Mob;Ljava/util/function/Supplier;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)V"), cancellable = true)
    private void paper$ageLockEvent(final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir, @Local(ordinal = 0) final ItemStack itemStack) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerToggleEntityAgeLockEvent(player, (Tadpole) (Object) this, itemStack, hand, !this.isAgeLocked(), AGE_LOCKED)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
    // Paper end - call PlayerToggleEntityAgeLockEvent

    @WrapOperation(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Bucketable;bucketMobPickup(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/Optional;"))
    private java.util.Optional<InteractionResult> paper$bucketEvent(final Player player, final InteractionHand hand, final net.minecraft.world.entity.LivingEntity entity, final Operation<java.util.Optional<InteractionResult>> original) {
        return net.fissionmc.backend.support.BucketableSupport.bucketMobPickup(player, hand, entity);
    }
}
