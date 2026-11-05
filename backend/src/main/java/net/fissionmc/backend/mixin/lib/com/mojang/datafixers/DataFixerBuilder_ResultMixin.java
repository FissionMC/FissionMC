package net.fissionmc.backend.mixin.lib.com.mojang.datafixers;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.datafixers.DataFixerBuilder;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/com/mojang/datafixers/DataFixerBuilder.java.patch (Perf: Cache DataFixerUpper Rewrite Rules on demand).
 * Paper computes minDataFixPrecacheVersion in the builder constructor and skips `versionKey < min` in the optimize
 * loop; iterating only the tail set from min is equivalent.
 */
@Mixin(DataFixerBuilder.Result.class)
public abstract class DataFixerBuilder_ResultMixin {
    @Shadow @Final DataFixerBuilder this$0;

    @ModifyExpressionValue(method = "optimize", at = @At(value = "INVOKE", target = "Lcom/mojang/datafixers/DataFixerUpper;fixerVersions()Lit/unimi/dsi/fastutil/ints/IntSortedSet;"))
    private IntSortedSet fission$skipPrecache(IntSortedSet fixerVersions) {
        final int dataVersion = ((DataFixerBuilderAccessorMixin) this.this$0).fission$dataVersion();
        final int minDataFixPrecacheVersion = Integer.getInteger("Paper.minPrecachedDatafixVersion", dataVersion + 1) * 10; // Paper - Perf: default to precache nothing - Mojang stores versions * 10 to allow for 'sub versions'
        return fixerVersions.tailSet(minDataFixPrecacheVersion); // Paper - Perf: Cache DataFixerUpper Rewrite Rules on demand
    }
}
