package net.fissionmc.backend.mixin.world.item;

import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/ItemCooldowns.java.patch */
@Mixin(ItemCooldowns.class)
public abstract class ItemCooldownsMixin {
    // Value type is the package-private ItemCooldowns.CooldownInstance; read through ItemCooldowns_CooldownInstanceMixin.
    @Shadow @Final private Map<Identifier, ?> cooldowns;
    @Shadow private int tickCount;

    // Paper start - util
    public int getRemainingCooldown(final Identifier cooldownGroup) {
        final Object cooldown = this.cooldowns.get(cooldownGroup);
        if (cooldown == null) {
            return 0;
        }
        return Math.max(0, ((ItemCooldowns_CooldownInstanceMixin) cooldown).fission$endTime() - this.tickCount);
    }
    // Paper end
}
