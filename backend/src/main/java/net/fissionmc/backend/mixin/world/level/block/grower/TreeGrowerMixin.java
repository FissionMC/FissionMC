package net.fissionmc.backend.mixin.world.level.block.grower;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: grower/TreeGrower.java.patch - record the TreeType of the feature that is about to be placed (SaplingBlock.treeType) for the
 * StructureGrowEvent. Paper calls setTreeType right after the feature holder is found; wrapping Registry#get gives the same last-set value.
 */
@Mixin(TreeGrower.class)
public abstract class TreeGrowerMixin {
    @WrapOperation(method = "growTree", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Registry;get(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;"))
    private Optional<Holder.Reference<Feature>> paper$setTreeType(final Registry<Feature> registry, final ResourceKey<Feature> key, final Operation<Optional<Holder.Reference<Feature>>> original) {
        final Optional<Holder.Reference<Feature>> holder = original.call(registry, key);
        if (holder.isPresent()) {
            try {
                SaplingBlock.treeType = org.bukkit.craftbukkit.CraftTreeType.fromFeature(key); // CraftBukkit
            } catch (final IllegalArgumentException ignored) {
                SaplingBlock.treeType = null; // unmapped feature: no StructureGrowEvent, the tree still grows
            }
        }
        return holder;
    }
}
