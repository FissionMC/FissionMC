package net.fissionmc.backend.mixin.world.level.storage;

import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** FissionMC: access to the private constructor for TagValueOutputMixin's Paper factories. */
@Mixin(TagValueOutput.class)
public interface TagValueOutputAccessorMixin {
    @Invoker("<init>")
    static TagValueOutput fission$new(ProblemReporter problemReporter, DynamicOps<Tag> ops, CompoundTag output) {
        throw new AssertionError();
    }
}
