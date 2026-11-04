package net.fissionmc.backend.mixin.server;

import net.minecraft.server.Services;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/server/Services.java.patch (PaperServices record lives in net.fissionmc.craft.compat) */
@Mixin(Services.class)
public abstract class ServicesMixin {
    // Paper start - add paper configuration files
    // TODO(behaviour): Paper adds this as a record component, filled by Services.create(..., OptionSet)
    private net.fissionmc.craft.compat.@org.jspecify.annotations.Nullable PaperServices paper;

    public net.fissionmc.craft.compat.PaperServices paper() {
        return java.util.Objects.requireNonNull(this.paper);
    }
    // Paper end - add paper configuration files
}
