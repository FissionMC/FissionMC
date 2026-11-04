package net.fissionmc.server.mixin;

import net.fissionmc.server.Hooks;
import net.minecraft.server.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Proves the loader: runs before anything else in vanilla's entry point. */
@Mixin(Main.class)
public abstract class MainMixin {
    @Inject(method = "main", at = @At("HEAD"))
    private static void fission$main(String[] args, CallbackInfo ci) {
        Hooks.onMain();
    }
}
