package net.fissionmc.backend.mixin.core.component;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/core/component/DataComponentPatch.java.patch */
@Mixin(DataComponentPatch.Builder.class)
public abstract class DataComponentPatch_BuilderMixin {
    @Shadow @Final private Reference2ObjectMap<DataComponentType<?>, Object> map;

    // CraftBukkit start
    public void copy(DataComponentPatch orig) {
        this.map.putAll(((DataComponentPatchAccessorMixin) (Object) orig).fission$map());
    }

    public void clear(DataComponentType<?> type) {
        this.map.remove(type);
    }

    public boolean isSet(DataComponentType<?> type) {
        return this.map.containsKey(type);
    }

    public boolean isEmpty() {
        return this.map.isEmpty();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (object instanceof DataComponentPatch.Builder patch) {
            return this.map.equals(((DataComponentPatch_BuilderAccessorMixin) (Object) patch).fission$map());
        }

        return false;
    }

    @Override
    public int hashCode() {
        return this.map.hashCode();
    }
    // CraftBukkit end
}
