package net.fissionmc.backend.mixin.world.entity.monster.skeleton;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/monster/skeleton/AbstractSkeleton.java.patch */
@Mixin(AbstractSkeleton.class)
public abstract class AbstractSkeletonMixin extends Monster {
    private boolean shouldBurnInDay = true; // Paper - shouldBurnInDay API
    // TODO(behaviour): "Paper.ShouldBurnInDay" save/load; onEquipItem(…, silent) override.

    protected AbstractSkeletonMixin(final EntityType<? extends Monster> type, final Level level) {
        super(type, level);
    }

    // Paper start - shouldBurnInDay API
    public boolean shouldBurnInDay() {
        return this.shouldBurnInDay;
    }

    public void setShouldBurnInDay(boolean shouldBurnInDay) {
        this.shouldBurnInDay = shouldBurnInDay;
    }

    @Override
    public boolean isSunBurnTick() {
        return this.shouldBurnInDay && super.isSunBurnTick();
    }
    // Paper end - shouldBurnInDay API
}
