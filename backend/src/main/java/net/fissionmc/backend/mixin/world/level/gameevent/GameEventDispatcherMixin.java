package net.fissionmc.backend.mixin.world.level.gameevent;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/gameevent/GameEventDispatcher.java.patch */
@Mixin(GameEventDispatcher.class)
public abstract class GameEventDispatcherMixin {
    @Shadow @Final private ServerLevel level;

    private int fission$apiRadius;

    // CraftBukkit start - GenericGameEvent (fired before the radius is used; the event can change it)
    @Inject(method = "post", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$genericGameEvent(final Holder<GameEvent> gameEvent, final Vec3 position, final GameEvent.Context context, final CallbackInfo ci) {
        int radius = gameEvent.value().notificationRadius();
        BlockPos center = BlockPos.containing(position);
        org.bukkit.event.world.GenericGameEvent apiEvent = new org.bukkit.event.world.GenericGameEvent(
            org.bukkit.craftbukkit.CraftGameEvent.minecraftHolderToBukkit(gameEvent), org.bukkit.craftbukkit.util.CraftLocation.toBukkit(center, this.level),
            (context.sourceEntity() == null) ? null : context.sourceEntity().getBukkitEntity(), radius, !org.bukkit.Bukkit.isPrimaryThread());
        if (!apiEvent.callEvent()) {
            ci.cancel();
            return;
        }
        this.fission$apiRadius = apiEvent.getRadius();
    }

    @ModifyExpressionValue(method = "post", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gameevent/GameEvent;notificationRadius()I"))
    private int craftbukkit$eventRadius(final int radius) {
        return this.fission$apiRadius;
    }
    // CraftBukkit end
}
