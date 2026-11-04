package net.fissionmc.backend.mixin.world.level;

import java.util.Arrays;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/Level.java.patch (ExplosionInteraction) */
@Mixin(Level.ExplosionInteraction.class)
public abstract class Level_ExplosionInteractionMixin {
    @Shadow @Final @Mutable private static Level.ExplosionInteraction[] $VALUES;

    private static final Level.ExplosionInteraction STANDARD = fission$addStandard(); // CraftBukkit - Add STANDARD which will always use Explosion.Effect.DESTROY
    // TODO(behaviour): ServerLevel#explode switch maps STANDARD -> Explosion.BlockInteraction.DESTROY (done in ServerLevelMixin#explode0); CODEC was built before STANDARD existed

    private static Level.ExplosionInteraction fission$addStandard() {
        final Level.ExplosionInteraction standard = Level_ExplosionInteractionAccessorMixin.fission$new("STANDARD", $VALUES.length, "standard");
        final Level.ExplosionInteraction[] values = Arrays.copyOf($VALUES, $VALUES.length + 1);
        values[values.length - 1] = standard;
        $VALUES = values;
        return standard;
    }
}
