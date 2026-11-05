package net.fissionmc.backend.mixin.lib.com.mojang.datafixers;

import com.mojang.datafixers.DataFixerBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DataFixerBuilder.class)
public interface DataFixerBuilderAccessorMixin {
    @Accessor("dataVersion")
    int fission$dataVersion();
}
