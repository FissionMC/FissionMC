package net.fissionmc.backend.mixin.world.entity.ai.goal;

import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link Goal} (paper-server/patches/sources/net/minecraft/world/entity/ai/goal/Goal.java.patch).
 */
@Mixin(Goal.class)
public abstract class GoalMixin {
    @Shadow @Final private EnumSet<Goal.Flag> flags;

    // Paper start - Mob Goal API
    public boolean hasFlag(final Goal.Flag flag) {
        return this.flags.contains(flag);
    }

    public void addFlag(final Goal.Flag flag) {
        this.flags.add(flag);
    }
    // Paper end - Mob Goal API

    // Paper start - Mob goal api
    private com.destroystokyo.paper.entity.ai.PaperGoal<?> paperGoal;
    public <T extends org.bukkit.entity.Mob> com.destroystokyo.paper.entity.ai.Goal<T> asPaperGoal() {
        if (this.paperGoal == null) {
            this.paperGoal = new com.destroystokyo.paper.entity.ai.PaperGoal<>((Goal) (Object) this);
        }
        //noinspection unchecked
        return (com.destroystokyo.paper.entity.ai.Goal<T>) this.paperGoal;
    }
    // Paper end - Mob goal api
}
