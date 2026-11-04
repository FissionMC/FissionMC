package net.fissionmc.backend.mixin.world.level.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/storage/TagValueInput.java.patch */
@Mixin(TagValueInput.class)
public abstract class TagValueInputMixin {
    // Paper start - utility methods
    private static ValueInput createGlobal(
        final ProblemReporter problemReporter,
        final CompoundTag compoundTag
    ) {
        return TagValueInput.create(problemReporter, net.minecraft.server.MinecraftServer.getServer().registryAccess(), compoundTag);
    }
    // Paper end - utility methods
}
