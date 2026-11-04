package net.fissionmc.backend.mixin.world.level;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** FissionMC: enum constructor access for Level_ExplosionInteractionMixin (adds Paper's STANDARD constant). */
@Mixin(Level.ExplosionInteraction.class)
public interface Level_ExplosionInteractionAccessorMixin {
    @Invoker("<init>")
    static Level.ExplosionInteraction fission$new(String name, int ordinal, String id) {
        throw new AssertionError();
    }
}
