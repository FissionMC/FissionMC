package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/monster/Creeper.java.patch */
@Mixin(Creeper.class)
public abstract class CreeperMixin {
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_IS_POWERED;
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_IS_IGNITED;

    @Shadow public abstract boolean isIgnited();

    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private @Nullable Entity entityIgniter; // CraftBukkit
    // TODO(behaviour): entityIgniter set in mobInteract/ignite; CreeperPowerEvent in thunderHit; setTarget/killedEntity overloads.

    // CraftBukkit start
    public void setPowered(boolean powered) {
        ((Entity) (Object) this).getEntityData().set(CreeperMixin.DATA_IS_POWERED, powered);
    }
    // CraftBukkit end

    // Paper start - Call CreeperIgniteEvent
    public void setIgnited(boolean ignited) {
        if (isIgnited() != ignited) {
            com.destroystokyo.paper.event.entity.CreeperIgniteEvent event = new com.destroystokyo.paper.event.entity.CreeperIgniteEvent((org.bukkit.entity.Creeper) ((Entity) (Object) this).getBukkitEntity(), ignited);
            if (event.callEvent()) {
                ((Entity) (Object) this).getEntityData().set(CreeperMixin.DATA_IS_IGNITED, event.isIgnited());
            }
        }
    }
    // Paper end - Call CreeperIgniteEvent
}
