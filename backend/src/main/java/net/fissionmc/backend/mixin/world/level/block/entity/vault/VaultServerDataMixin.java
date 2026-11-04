package net.fissionmc.backend.mixin.world.level.block.entity.vault;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/vault/VaultServerData.java.patch
 * TODO(behaviour): addToRewardedPlayers(Player) delegates to the UUID variant (same result).
 */
@Mixin(VaultServerData.class)
public abstract class VaultServerDataMixin {
    @Shadow @Final private Set<UUID> rewardedPlayers;

    @Shadow
    private void markChanged() {
        throw new AssertionError();
    }

    // Paper start - Vault API
    public boolean addToRewardedPlayers(final java.util.UUID player) {
        final boolean removed = this.rewardedPlayers.add(player);
    // Paper end - Vault API
        if (this.rewardedPlayers.size() > 128) {
            Iterator<UUID> iterator = this.rewardedPlayers.iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        this.markChanged();
        return removed; // Paper - Vault API
    }

    public boolean removeFromRewardedPlayers(final UUID uuid) {
        if (this.rewardedPlayers.remove(uuid)) {
            this.markChanged();
            return true;
        }

        return false;
    }
    // Paper end - Vault API
}
