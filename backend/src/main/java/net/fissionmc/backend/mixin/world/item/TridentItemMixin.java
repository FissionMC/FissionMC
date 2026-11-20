package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/TridentItem.java.patch - PlayerRiptideEvent (the PlayerLaunchProjectileEvent throw hunk is not ported yet). */
@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
    @Inject(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V"), cancellable = true)
    private void paper$riptideEvent(final ItemStack itemStack, final net.minecraft.world.level.Level level, final net.minecraft.world.entity.LivingEntity entity, final int remainingTime,
                                    final CallbackInfoReturnable<Boolean> cir, @Local(ordinal = 2) final float xd, @Local(ordinal = 3) final float yd, @Local(ordinal = 4) final float zd) {
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerRiptideEvent((Player) entity, itemStack, xd, yd, zd)) {
            cir.setReturnValue(false);
        }
    }
}
