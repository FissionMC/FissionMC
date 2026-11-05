package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/entity/monster/Enderman.java.patch
 * Done: EndermanEscapeEvent (runaway/indirect/drown+crit/stare), EndermanAttackPlayerEvent, EntityChangeBlockEvent for take/leave (goal mixins).
 * TODO(behaviour): setTarget(reason) overload, no-chunk-load getBlockStateIfLoaded.
 */
@Mixin(Enderman.class)
public abstract class EndermanMixin {
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_CREEPY;
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_STARED_AT;

    // Paper start
    public void setCreepy(boolean creepy) {
        ((Entity) (Object) this).getEntityData().set(EndermanMixin.DATA_CREEPY, creepy);
    }

    public void setHasBeenStaredAt(boolean hasBeenStaredAt) {
        ((Entity) (Object) this).getEntityData().set(EndermanMixin.DATA_STARED_AT, hasBeenStaredAt);
    }
    // Paper end

    private boolean tryEscape(com.destroystokyo.paper.event.entity.EndermanEscapeEvent.Reason reason) {
        return new com.destroystokyo.paper.event.entity.EndermanEscapeEvent((org.bukkit.craftbukkit.entity.CraftEnderman) ((Entity) (Object) this).getBukkitEntity(), reason).callEvent();
    }

    private boolean fission$runaway;

    // customServerAiStep: `... && this.tryEscape(RUNAWAY)` guards both setTarget(null) and teleport()
    @WrapWithCondition(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Enderman;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private boolean fission$runawayTarget(final Enderman self, final net.minecraft.world.entity.LivingEntity target) {
        this.fission$runaway = this.tryEscape(com.destroystokyo.paper.event.entity.EndermanEscapeEvent.Reason.RUNAWAY); // Paper - EndermanEscapeEvent
        return this.fission$runaway;
    }

    @WrapWithCondition(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Enderman;teleport()Z"))
    private boolean fission$runawayTeleport(final Enderman self) {
        return this.fission$runaway;
    }

    @WrapWithCondition(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Enderman;repeatedlyTryToTeleport()V"))
    private boolean fission$indirect(final Enderman self) {
        return this.tryEscape(com.destroystokyo.paper.event.entity.EndermanEscapeEvent.Reason.INDIRECT); // Paper - EndermanEscapeEvent
    }

    @WrapWithCondition(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Enderman;teleport()Z"))
    private boolean fission$critical(final Enderman self, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) DamageSource source) {
        return this.tryEscape(source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING) ? com.destroystokyo.paper.event.entity.EndermanEscapeEvent.Reason.DROWN : com.destroystokyo.paper.event.entity.EndermanEscapeEvent.Reason.CRITICAL_HIT); // Paper - EndermanEscapeEvent
    }

    // isBeingStaredBy: EndermanAttackPlayerEvent
    @ModifyReturnValue(method = "isBeingStaredBy", at = @At("RETURN"))
    private boolean fission$attackPlayerEvent(final boolean shouldAttack, final Player player) {
        final com.destroystokyo.paper.event.entity.EndermanAttackPlayerEvent event = new com.destroystokyo.paper.event.entity.EndermanAttackPlayerEvent((org.bukkit.entity.Enderman) ((Entity) (Object) this).getBukkitEntity(), (org.bukkit.entity.Player) player.getBukkitEntity());
        event.setCancelled(!shouldAttack);
        return event.callEvent();
    }
}
