package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/monster/Phantom.java.patch */
@Mixin(Phantom.class)
public abstract class PhantomMixin extends Mob {
    // Paper start
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private java.util.@org.jspecify.annotations.Nullable UUID spawningEntity;
    private boolean shouldBurnInDay = true;
    // Paper end
    // TODO(behaviour): "Paper.SpawningEntity"/"Paper.ShouldBurnInDay" save/load.

    protected PhantomMixin(final EntityType<? extends Mob> type, final Level level) {
        super(type, level);
    }

    // Paper start
    @Override
    public boolean isSunBurnTick() {
        return this.shouldBurnInDay && super.isSunBurnTick();
    }
    // Paper end
}
