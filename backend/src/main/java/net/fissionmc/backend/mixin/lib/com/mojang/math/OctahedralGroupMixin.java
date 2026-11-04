package net.fissionmc.backend.mixin.lib.com.mojang.math;

import com.mojang.math.OctahedralGroup;
import java.util.Map;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/com/mojang/math/OctahedralGroup.java.patch */
@Mixin(OctahedralGroup.class)
public abstract class OctahedralGroupMixin {
    @Shadow private Map<Direction, Direction> rotatedDirections;

    @Shadow public abstract Direction rotate(Direction direction);

    // Paper start - Avoid Lazy Initialization for Enum Fields
    public void initializeRotationDirections() {
        // Vanilla rotate(Direction) lazily fills rotatedDirections (Paper moved that body here); calling it once
        // initialises the map identically.
        if (this.rotatedDirections == null) {
            this.rotate(Direction.NORTH);
        }
    }
    // TODO(behaviour): Paper adds a static initializer calling initializeRotationDirections() for every value,
    // and strips the lazy init out of rotate(Direction).
    // Paper end - Avoid Lazy Initialization for Enum Fields
}
