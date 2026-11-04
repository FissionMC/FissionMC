package net.fissionmc.backend.mixin.world.entity.ai.goal;

import net.minecraft.world.entity.ai.goal.Goal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Constructor invoker used by {@link Goal_FlagMixin} to create the {@code UNKNOWN_BEHAVIOR} enum constant. */
@Mixin(Goal.Flag.class)
public interface Goal_FlagInvokerMixin {
    @Invoker("<init>")
    static Goal.Flag fission$create(String name, int ordinal) {
        throw new AssertionError();
    }
}
