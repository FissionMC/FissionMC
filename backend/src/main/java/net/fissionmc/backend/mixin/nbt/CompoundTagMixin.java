package net.fissionmc.backend.mixin.nbt;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/nbt/CompoundTag.java.patch */
@Mixin(CompoundTag.class)
public abstract class CompoundTagMixin {
    @Shadow public abstract Tag get(String name);

    // Paper start - option to read via codec without logging errors
    // The below methods are 1 to 1 copies of the read methods without the logging part.
    public <T> Optional<T> readQuiet(String name, Codec<T> codec) {
        return this.readQuiet(name, codec, NbtOps.INSTANCE);
    }

    public <T> Optional<T> readQuiet(String name, Codec<T> codec, DynamicOps<Tag> ops) {
        Tag tag = this.get(name);
        return tag == null
            ? Optional.empty()
            : codec.parse(ops, tag).resultOrPartial();
    }

    public <T> Optional<T> readQuiet(MapCodec<T> codec) {
        return this.readQuiet(codec, NbtOps.INSTANCE);
    }

    public <T> Optional<T> readQuiet(MapCodec<T> codec, DynamicOps<Tag> ops) {
        return codec.decode(ops, ops.getMap((Tag) (Object) this).getOrThrow()).resultOrPartial();
    }
    // Paper end - option to read via codec without logging errors
}
