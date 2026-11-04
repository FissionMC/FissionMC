package net.fissionmc.backend.mixin.world.entity.projectile.arrow;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/projectile/arrow/ThrownTrident.java.patch */
@Mixin(ThrownTrident.class)
public abstract class ThrownTridentMixin {
    @Shadow @Final private static EntityDataAccessor<Byte> ID_LOYALTY;
    @Shadow @Final private static EntityDataAccessor<Boolean> ID_FOIL;

    // Paper start
    public void setFoil(boolean foil) {
        ((Entity) (Object) this).getEntityData().set(ThrownTridentMixin.ID_FOIL, foil);
    }

    public int getLoyalty() {
        return ((Entity) (Object) this).getEntityData().get(ThrownTridentMixin.ID_LOYALTY);
    }

    public void setLoyalty(byte loyalty) {
        ((Entity) (Object) this).getEntityData().set(ThrownTridentMixin.ID_LOYALTY, loyalty);
    }
    // Paper end
}
