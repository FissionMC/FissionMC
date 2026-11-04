package net.fissionmc.backend.mixin.world.item;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the package-private ItemCooldowns.CooldownInstance record (used by ItemCooldownsMixin). */
@Mixin(targets = "net.minecraft.world.item.ItemCooldowns$CooldownInstance")
public interface ItemCooldowns_CooldownInstanceMixin {
    @Accessor("endTime")
    int fission$endTime();
}
