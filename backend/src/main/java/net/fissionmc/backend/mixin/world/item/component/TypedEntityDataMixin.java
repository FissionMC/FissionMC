package net.fissionmc.backend.mixin.world.item.component;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.component.TypedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link TypedEntityData} (paper-server/patches/sources/net/minecraft/world/item/component/TypedEntityData.java.patch).
 */
@Mixin(TypedEntityData.class)
public abstract class TypedEntityDataMixin<IdType> {
    @Shadow @Final private IdType type;
    @Shadow @Final private CompoundTag tag;

    // Paper start - utils for item meta
    private static <IdType> TypedEntityData<IdType> decode(final Codec<IdType> idTypeCodec, final CompoundTag tag) {
        return TypedEntityData.codec(idTypeCodec).decode(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElseThrow().getFirst();
    }

    private static TypedEntityData<EntityType<?>> decodeEntity(final CompoundTag tag) {
        return decode(net.minecraft.world.entity.EntityType.CODEC, tag);
    }

    private static TypedEntityData<net.minecraft.world.level.block.entity.BlockEntityType<?>> decodeBlockEntity(final CompoundTag tag) {
        return decode(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.byNameCodec(), tag);
    }

    public CompoundTag copyTagWithEntityId() {
        final CompoundTag tag = this.tag.copy();
        tag.putString("id", EntityType.getKey((EntityType<?>) this.type).toString());
        return tag;
    }

    public CompoundTag copyTagWithBlockEntityId() {
        final CompoundTag tag = this.tag.copy();
        tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey((net.minecraft.world.level.block.entity.BlockEntityType<?>) this.type).toString());
        return tag;
    }
    // Paper end - utils for item meta
}
