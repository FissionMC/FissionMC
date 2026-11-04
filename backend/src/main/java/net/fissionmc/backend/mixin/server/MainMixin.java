package net.fissionmc.backend.mixin.server;

import net.fissionmc.backend.Hooks;
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

    // Paper: sources/net/minecraft/server/Main.java.patch - main(OptionSet) replaces main(String[]) (CraftBukkit)
    // TODO(behaviour): FissionMC boots through vanilla main(String[]); Paper's OptionSet entry point (EULA/universe/
    //  bukkit-settings handling, Services.create(..., options), DedicatedServer construction) is not ported yet.
    private static void main(final joptsimple.OptionSet options) {
        throw new UnsupportedOperationException("FissionMC starts the server through net.minecraft.server.Main#main(String[])");
    }
}
