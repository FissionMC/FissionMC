package net.fissionmc.backend.mixin.util;

import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/util/Util.java.patch */
@Mixin(Util.class)
public abstract class UtilMixin {
    private static final double COLLISION_EPSILON = 1.0E-7; // Paper - Check distance in entity interactions
}
