package net.fissionmc.backend.mixin.world.entity.monster.zombie;

import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/monster/zombie/Zombie.java.patch */
@Mixin(Zombie.class)
public abstract class ZombieMixin {
    private boolean shouldBurnInDay = true; // Paper - Add more Zombie API
    // TODO(behaviour): "Paper.ShouldBurnInDay" save/load.

    @Inject(method = "isSunSensitive", at = @At("HEAD"), cancellable = true)
    private void fission$isSunSensitive(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.shouldBurnInDay); // Paper - Add more Zombie API
    }

    // Paper start - Add more Zombie API
    public void setShouldBurnInDay(boolean shouldBurnInDay) {
        this.shouldBurnInDay = shouldBurnInDay;
    }
    // Paper end - Add more Zombie API
}
