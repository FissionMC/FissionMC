package net.fissionmc.backend.mixin.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.PlaceholderLookupProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/util/PlaceholderLookupProvider.java.patch (anonymous RegistryInfoLookup) */
@Mixin(targets = "net.minecraft.util.PlaceholderLookupProvider$1")
public abstract class PlaceholderLookupProvider_1Mixin {
    @Shadow(remap = false) @Final PlaceholderLookupProvider this$0;

    // Paper start add method to get the value for pre-filling builders in the reg mod API
    public HolderLookup.Provider lookupForValueCopyViaBuilders() {
        return ((PlaceholderLookupProviderAccessorMixin) (Object) this.this$0).fission$context(); // PlaceholderLookupProvider.this.context
    }
    // Paper end - add method to get the value for pre-filling builders in the reg mod API
}
