package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BaseContainerBlockEntity.java.patch
 * Done: canOpen calls BlockLockCheckEvent, sendChestLockedNotifications checks the event.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin {
    @Shadow private LockCode lockKey;

    // Paper - Call BlockLockCheckEvent
    @Inject(method = "canOpen", at = @At("HEAD"), cancellable = true)
    private void paper$lockCheck(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        final BaseContainerBlockEntity self = (BaseContainerBlockEntity) (Object) this;
        cir.setReturnValue(org.bukkit.craftbukkit.event.CraftEventFactory.callBlockLockCheckEvent(self, this.lockKey, self.getDisplayName(), player));
    }

    // Paper - BlockLockCheckEvent: the event supplies its own message and sound
    @Inject(method = "sendChestLockedNotifications", at = @At("HEAD"), cancellable = true)
    private static void paper$lockedNotifications(final Vec3 pos, final Player player, final Component displayName, final CallbackInfo ci) {
        if (org.bukkit.craftbukkit.event.CraftEventFactory.sendChestLockedNotifications(pos)) {
            ci.cancel();
        }
    }

    // CraftBukkit start
    public org.bukkit.@Nullable Location getLocation() {
        final BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() == null) return null;
        return org.bukkit.craftbukkit.util.CraftLocation.toBukkit(self.getBlockPos(), self.getLevel());
    }
    // CraftBukkit end
}
