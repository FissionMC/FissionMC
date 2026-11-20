package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Optional;
import net.fissionmc.backend.support.BucketableSupport;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/Bucketable.java.patch (PlayerBucketEntityEvent / PlayerBucketFishEvent for SulfurCube) */
@Mixin(net.minecraft.world.entity.monster.cubemob.SulfurCube.class)
public abstract class SulfurCubeBucketMixin {
    @WrapOperation(method = "mobInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Bucketable;bucketMobPickup(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/Optional;"))
    private Optional<InteractionResult> paper$bucketEvent(final Player player, final InteractionHand hand, final LivingEntity entity, final Operation<Optional<InteractionResult>> original) {
        return BucketableSupport.bucketMobPickup(player, hand, entity);
    }
}
