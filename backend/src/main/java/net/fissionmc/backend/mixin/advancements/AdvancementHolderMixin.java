package net.fissionmc.backend.mixin.advancements;

import net.minecraft.advancements.AdvancementHolder;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/advancements/AdvancementHolder.java.patch */
@Mixin(AdvancementHolder.class)
public abstract class AdvancementHolderMixin {
    // CraftBukkit start
    public final org.bukkit.advancement.Advancement toBukkit() {
        return new org.bukkit.craftbukkit.advancement.CraftAdvancement((AdvancementHolder) (Object) this);
    }
    // CraftBukkit end
}
