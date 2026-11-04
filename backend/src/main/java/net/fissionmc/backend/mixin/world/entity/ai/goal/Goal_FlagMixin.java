package net.fissionmc.backend.mixin.world.entity.ai.goal;

import java.util.Arrays;
import net.minecraft.world.entity.ai.goal.Goal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper adds the constant {@code UNKNOWN_BEHAVIOR} to {@link Goal.Flag} (Goal.java.patch, "Paper - add UNKNOWN_BEHAVIOR").
 * FissionMC appends it as the last constant (Paper declares it first, so its ordinal differs; nothing relies on it).
 */
@Mixin(Goal.Flag.class)
public abstract class Goal_FlagMixin {
    @Shadow @Final @Mutable private static Goal.Flag[] $VALUES;

    private static Goal.Flag UNKNOWN_BEHAVIOR; // Paper - add UNKNOWN_BEHAVIOR

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void fission$addUnknownBehavior(CallbackInfo ci) {
        UNKNOWN_BEHAVIOR = Goal_FlagInvokerMixin.fission$create("UNKNOWN_BEHAVIOR", $VALUES.length);
        Goal.Flag[] values = Arrays.copyOf($VALUES, $VALUES.length + 1);
        values[values.length - 1] = UNKNOWN_BEHAVIOR;
        $VALUES = values;
    }
}
