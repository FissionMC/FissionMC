package net.fissionmc.backend.mixin.world.entity.animal.sheep;

import net.minecraft.world.entity.animal.sheep.Sheep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/entity/animal/sheep/Sheep.java.patch
 * done: SheepRegrowWoolEvent. Not ported: PlayerShearEntityEvent / custom shear drops in mobInteract.
 */
@Mixin(Sheep.class)
public abstract class SheepMixin {
    @Inject(method = "ate", at = @At("HEAD"), cancellable = true)
    private void paper$regrowWool(final CallbackInfo ci) {
        if (!new org.bukkit.event.entity.SheepRegrowWoolEvent((org.bukkit.entity.Sheep) ((Sheep) (Object) this).getBukkitEntity()).callEvent()) ci.cancel(); // CraftBukkit
    }
}
