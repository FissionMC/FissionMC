package net.fissionmc.backend.mixin.world.item.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/item/component/CustomData.java.patch
 * TODO(behaviour): COMPOUND_TAG_CODEC picks SNBT for serialization when SERIALIZE_CUSTOM_AS_SNBT is set (Item serialization as json).
 */
@Mixin(CustomData.class)
public abstract class CustomDataMixin {
    @Shadow @Final private CompoundTag tag;

    // Paper start - Item serialization as json
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static ThreadLocal<Boolean> SERIALIZE_CUSTOM_AS_SNBT = ThreadLocal.withInitial(() -> false);
    // Paper end - Item serialization as json

    // Paper start - expose unsafe internal compound tag for read only access
    @Deprecated
    public CompoundTag getUnsafe() {
        return this.tag;
    }

    public boolean contains(String key) {
        return this.tag.contains(key);
    }
    // Paper end - expose unsafe internal compound tag for read only access
}
