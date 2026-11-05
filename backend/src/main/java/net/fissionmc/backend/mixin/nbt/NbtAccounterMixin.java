package net.fissionmc.backend.mixin.nbt;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/nbt/NbtAccounter.java.patch */
@Mixin(NbtAccounter.class)
public abstract class NbtAccounterMixin {
    @Shadow @Final private int maxDepth;
    @Shadow private int depth;

    // Paper start - Track codec depth
    public void pushDepth(final int depth) {
        if (this.depth + depth >= this.maxDepth) {
            throw new NbtAccounterException("Tried to read NBT tag with too high complexity, depth > " + this.maxDepth);
        } else {
            this.depth += depth;
        }
    }
    // Paper end - Track codec depth
}
