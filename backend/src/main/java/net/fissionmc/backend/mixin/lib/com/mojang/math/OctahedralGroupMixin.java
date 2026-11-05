package net.fissionmc.backend.mixin.lib.com.mojang.math;

import com.mojang.math.OctahedralGroup;
import java.util.Map;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void fission$initializeAllRotationDirections(CallbackInfo ci) {
        for (OctahedralGroup octahedralGroup : OctahedralGroup.values()) {
            octahedralGroup.initializeRotationDirections();
        }
    }
    // rotate(Direction) keeps its (now never taken) lazy-init branch.
    // Paper end - Avoid Lazy Initialization for Enum Fields
}
