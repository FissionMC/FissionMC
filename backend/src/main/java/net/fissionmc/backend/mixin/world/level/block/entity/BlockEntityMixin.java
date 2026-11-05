package net.fissionmc.backend.mixin.world.level.block.entity;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BlockEntity.java.patch
 * TODO(behaviour): loadAdditional clears and reads "PublicBukkitValues" into persistentDataContainer, saveAdditional
 * stores it; applyComponents delegates to applyComponentsSet; getPosFromTag accepts a null ChunkPos.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Shadow protected @Nullable Level level;
    @Shadow @Final protected BlockPos worldPosition;
    @Shadow private DataComponentMap components;

    @Shadow protected abstract void applyImplicitComponents(DataComponentGetter components);

    // CraftBukkit start - data containers
    // Surface: public final (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private static final org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry();
    private final org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer persistentDataContainer = new org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer(DATA_TYPE_REGISTRY); // Paper - always init
    // CraftBukkit end

    // CraftBukkit start
    public final Set<DataComponentType<?>> applyComponentsSet(final DataComponentMap prototype, final DataComponentPatch patch) {
        // CraftBukkit end
        final Set<DataComponentType<?>> implicitComponents = new HashSet<>();
        implicitComponents.add(DataComponents.BLOCK_ENTITY_DATA);
        implicitComponents.add(DataComponents.BLOCK_STATE);
        final DataComponentMap fullView = PatchedDataComponentMap.fromPatch(prototype, patch);
        this.applyImplicitComponents(new net.fissionmc.backend.support.ImplicitComponentGetter(implicitComponents, fullView)); // FissionMC: was an anonymous class (not loadable inside a mixin)
        DataComponentPatch newPatch = patch.forget(implicitComponents::contains);
        this.components = newPatch.split().added();
        // CraftBukkit start
        implicitComponents.remove(DataComponents.BLOCK_ENTITY_DATA); // Remove as never actually added by applyImplicitComponents
        return implicitComponents;
        // CraftBukkit end
    }

    // CraftBukkit start - add method
    public org.bukkit.inventory.@Nullable InventoryHolder getOwner() {
        return getOwner(true);
    }

    public org.bukkit.inventory.@Nullable InventoryHolder getOwner(boolean useSnapshot) {
        if (this.level == null) return null;
        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(this.level, this.worldPosition);
        org.bukkit.block.BlockState state = block.getState(useSnapshot); // Paper
        return state instanceof final org.bukkit.inventory.InventoryHolder inventoryHolder ? inventoryHolder : null;
    }
    // CraftBukkit end

    // Paper start - Sanitize sent data
    public CompoundTag sanitizeSentNbt(CompoundTag tag) {
        tag.remove("PublicBukkitValues");

        return tag;
    }
    // Paper end - Sanitize sent data
}
