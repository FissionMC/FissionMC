package net.fissionmc.backend.mixin.world.level.levelgen.structure.templatesystem;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings.java.patch (palette widened via surface) */
@Mixin(StructurePlaceSettings.class)
public abstract class StructurePlaceSettingsMixin {
    @Shadow private int palette;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$init(CallbackInfo ci) {
        this.palette = -1; // CraftBukkit - Set initial value so we know if the palette has been set forcefully
    }
    // TODO(behaviour): getRandomPalette hunk (use the forced palette when set)
}
