package net.fissionmc.backend.mixin.server.bossevents;

import net.minecraft.server.bossevents.CustomBossEvent;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/server/bossevents/CustomBossEvent.java.patch */
@Mixin(CustomBossEvent.class)
public abstract class CustomBossEventMixin {
    // CraftBukkit start
    private org.bukkit.boss.@org.jspecify.annotations.Nullable KeyedBossBar bossBar;

    public org.bukkit.boss.KeyedBossBar getBukkitEntity() {
        if (this.bossBar == null) {
            this.bossBar = new org.bukkit.craftbukkit.boss.CraftKeyedBossbar((CustomBossEvent) (Object) this);
        }
        return this.bossBar;
    }
    // CraftBukkit end
}
