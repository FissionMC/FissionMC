package net.fissionmc.backend.mixin.world.level.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/storage/TagValueOutput.java.patch */
@Mixin(TagValueOutput.class)
public abstract class TagValueOutputMixin {
    // Paper start - utility methods
    private static TagValueOutput createWrappingGlobal(
        final ProblemReporter problemReporter,
        final CompoundTag output
    ) {
        return TagValueOutputAccessorMixin.fission$new(problemReporter, NbtOps.INSTANCE, output);
    }

    private static TagValueOutput createWrappingWithContext(
        final ProblemReporter problemReporter,
        final HolderLookup.Provider lookup,
        final CompoundTag output
    ) {
        return TagValueOutputAccessorMixin.fission$new(problemReporter, lookup.createSerializationContext(NbtOps.INSTANCE), output);
    }
    // Paper end - utility methods
}
