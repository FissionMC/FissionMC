package net.fissionmc.backend.mixin.world;

import net.minecraft.world.LockCode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/LockCode.java.patch
 * TODO(behaviour): Paper routes canUnlock(Player) through canUnlock(Player, ItemStack) (same result).
 */
@Mixin(LockCode.class)
public abstract class LockCodeMixin {
    @Shadow public abstract boolean unlocksWith(ItemStack itemStack);

    // Paper start - allow overriding key item
    public boolean canUnlock(Player player, final ItemStack item) {
        return player.isSpectator() || this.unlocksWith(item); // Paper - allow overriding key item
    }
    // Paper end - allow overriding key item
}
