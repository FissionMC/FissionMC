package net.fissionmc.backend.mixin.world.entity.projectile.arrow;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/projectile/arrow/AbstractArrow.java.patch */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin extends Projectile {
    @Shadow private IntOpenHashSet piercingIgnoreEntityIds;

    @Shadow public abstract byte getPierceLevel();

    protected AbstractArrowMixin(final EntityType<? extends Projectile> type, final Level level) {
        super(type, level);
    }

    // Paper start - Fix cancelling ProjectileHitEvent for piercing arrows
    @Override
    public ProjectileDeflection preHitTargetOrDeflectSelf(HitResult hitResult) {
        if (hitResult instanceof EntityHitResult entityHitResult && this.hitCancelled && this.getPierceLevel() > 0) {
            if (this.piercingIgnoreEntityIds == null) {
                this.piercingIgnoreEntityIds = new IntOpenHashSet(5);
            }
            this.piercingIgnoreEntityIds.add(entityHitResult.getEntity().getId());
        }
        return super.preHitTargetOrDeflectSelf(hitResult);
    }
    // Paper end - Fix cancelling ProjectileHitEvent for piercing arrows
}
