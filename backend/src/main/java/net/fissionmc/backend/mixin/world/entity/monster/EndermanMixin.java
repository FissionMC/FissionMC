package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/monster/Enderman.java.patch */
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
}
