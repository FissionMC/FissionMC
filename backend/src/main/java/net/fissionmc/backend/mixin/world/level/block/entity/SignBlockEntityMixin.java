package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/SignBlockEntity.java.patch
 * TODO(behaviour): getSlotPlayerIsFacing(Player) delegates here (same result); SignChangeEvent in updateSignText.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
    // Paper start - More Sign Block API
    public SignTextSlot getSlotPlayerIsFacing(final double x, final double z) {
        // Paper end - More Sign Block API
        final SignBlockEntity self = (SignBlockEntity) (Object) this;
        if (self.getBlockState().getBlock() instanceof SignBlock sign) {
            Vec3 signPositionOffset = sign.getSignHitboxCenterPosition(self.getBlockState());
            double xd = x - (self.getBlockPos().getX() + signPositionOffset.x); // Paper - More Sign Block API
            double zd = z - (self.getBlockPos().getZ() + signPositionOffset.z); // Paper - More Sign Block API
            float signYRot = sign.getYRotationDegrees(self.getBlockState());
            float playerYRot = (float)(Mth.atan2(zd, xd) * 180.0F / (float)Math.PI) - 90.0F;
            return Mth.degreesDifferenceAbs(signYRot, playerYRot) <= 90.0F ? SignTextSlot.FRONT : SignTextSlot.BACK;
        }
        return SignTextSlot.FRONT;
    }
}
