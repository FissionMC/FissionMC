package net.fissionmc.backend.mixin.server.level;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CraftBukkit: sources/net/minecraft/server/level/ServerPlayer.java.patch sets {@code this.bukkitPickUpLoot = true} in the constructor.
 * ServerPlayerMixin only has it as a TODO, so {@code ItemEntity.playerTouch} saw {@code getCanPickupItems() == false}, cancelled
 * PlayerPickupItemEvent and no dropped item was ever picked up. New file (not an edit of ServerPlayerMixin) to stay merge-safe with the open wt2/player branch.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPickupMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$pickupDefault(CallbackInfo ci) {
        ((LivingEntity) (Object) this).bukkitPickUpLoot = true; // CraftBukkit
    }
}
