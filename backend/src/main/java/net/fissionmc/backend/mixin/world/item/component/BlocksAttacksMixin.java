package net.fissionmc.backend.mixin.world.item.component;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/component/BlocksAttacks.java.patch (PlayerShieldDisableEvent; the attacker overload of disable, called from Player.blockUsingItem) */
@Mixin(BlocksAttacks.class)
public abstract class BlocksAttacksMixin {
    @Shadow @Final private Optional<Holder<SoundEvent>> disableSound;

    @Shadow public abstract float disableCooldownScale();

    public void disable(final ServerLevel level, final LivingEntity user, final float baseSeconds, final ItemStack blockingWith, final LivingEntity attacker) { // Paper
        final float seconds = baseSeconds * this.disableCooldownScale();
        int cooldownTicks = seconds > 0.0F ? Math.round(seconds * 20.0F) : 0; // vanilla disableBlockingForTicks
        if (cooldownTicks > 0) {
            if (user instanceof Player player) {
                // Paper start
                final io.papermc.paper.event.player.PlayerShieldDisableEvent shieldDisableEvent = new io.papermc.paper.event.player.PlayerShieldDisableEvent((org.bukkit.entity.Player) player.getBukkitEntity(), attacker.getBukkitEntity(), cooldownTicks);
                if (!shieldDisableEvent.callEvent()) return;
                player.getCooldowns().addCooldown(blockingWith, shieldDisableEvent.getCooldown());
                // Paper end
            }

            user.stopUsingItem();
            this.disableSound.ifPresent(sound -> level.playSound(null, user.getX(), user.getY(), user.getZ(), sound, user.getSoundSource(), 0.8F, 0.8F + level.getRandom().nextFloat() * 0.4F));
        }
    }
}
