package net.fissionmc.backend.mixin.resources;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/resources/RegistryOps.java.patch */
@Mixin(RegistryOps.HolderLookupAdapter.class)
public abstract class RegistryOps_HolderLookupAdapterMixin {
    @Shadow @Final private HolderLookup.Provider lookupProvider;

    // Paper start - add method to get the value for pre-filling builders in the reg mod API
    public HolderLookup.Provider lookupForValueCopyViaBuilders() {
        return this.lookupProvider;
    }
    // Paper end - add method to get the value for pre-filling builders in the reg mod API
}
