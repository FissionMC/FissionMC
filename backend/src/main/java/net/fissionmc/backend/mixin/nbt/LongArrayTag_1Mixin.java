package net.fissionmc.backend.mixin.nbt;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/nbt/LongArrayTag.java.patch (TYPE.readAccounted) */
@Mixin(targets = "net.minecraft.nbt.LongArrayTag$1")
public abstract class LongArrayTag_1Mixin {
    @ModifyExpressionValue(method = "readAccounted", at = @At(value = "INVOKE", target = "Ljava/io/DataInput;readInt()I", ordinal = 0))
    private static int spigot$checkLength(int length) {
        com.google.common.base.Preconditions.checkArgument(length < 1 << 24); // Spigot
        return length;
    }
}
