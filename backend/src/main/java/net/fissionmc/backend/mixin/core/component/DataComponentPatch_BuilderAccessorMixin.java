package net.fissionmc.backend.mixin.core.component;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to another builder's {@code map} for {@link DataComponentPatch_BuilderMixin#equals}. */
@Mixin(DataComponentPatch.Builder.class)
public interface DataComponentPatch_BuilderAccessorMixin {
    @Accessor("map")
    Reference2ObjectMap<DataComponentType<?>, Object> fission$map();
}
