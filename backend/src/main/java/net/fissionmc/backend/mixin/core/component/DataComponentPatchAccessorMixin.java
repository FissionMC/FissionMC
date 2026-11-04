package net.fissionmc.backend.mixin.core.component;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the package-private {@code DataComponentPatch.map} for {@link DataComponentPatch_BuilderMixin#copy}. */
@Mixin(DataComponentPatch.class)
public interface DataComponentPatchAccessorMixin {
    @Accessor("map")
    Reference2ObjectMap<DataComponentType<?>, Object> fission$map();
}
