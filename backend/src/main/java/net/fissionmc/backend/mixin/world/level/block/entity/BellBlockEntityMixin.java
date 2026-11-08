package net.fissionmc.backend.mixin.world.level.block.entity;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.tags.EntityTypeTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BellBlockEntity.java.patch
 * done: BellResonateEvent (via CraftEventFactory.handleBellResonateEvent), BellRevealRaiderEvent.
 * left: the "bell block entity memory leak" cleanups of nearbyEntities.
 */
@Mixin(BellBlockEntity.class)
public abstract class BellBlockEntityMixin {
    // Paper start - call bell resonate event and bell reveal raider event
    @Inject(method = "makeRaidersGlow", at = @At("HEAD"), cancellable = true)
    private static void paper$resonateEvents(final Level level, final BlockPos blockPos, final List<LivingEntity> nearbyEntities, final CallbackInfo ci) {
        final List<org.bukkit.entity.LivingEntity> inRangeRaiders = nearbyEntities.stream()
            .filter(e -> e.isAlive() && !e.isRemoved() && blockPos.closerToCenterThan(e.position(), 48.0) && e.is(EntityTypeTags.RAIDERS))
            .map(e -> (org.bukkit.entity.LivingEntity) e.getBukkitEntity()).toList();
        org.bukkit.craftbukkit.event.CraftEventFactory.handleBellResonateEvent(level, blockPos, inRangeRaiders).forEach(raider -> {
            // Paper start - Add BellRevealRaiderEvent
            if (!new io.papermc.paper.event.block.BellRevealRaiderEvent(org.bukkit.craftbukkit.block.CraftBlock.at(raider.level(), blockPos), (org.bukkit.entity.Raider) raider.getBukkitEntity()).callEvent()) return;
            // Paper end - Add BellRevealRaiderEvent
            raider.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60));
        });
        ci.cancel();
    }
    // Paper end - call bell resonate event and bell reveal raider event
}
