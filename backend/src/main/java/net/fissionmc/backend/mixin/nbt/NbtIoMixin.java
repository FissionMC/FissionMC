package net.fissionmc.backend.mixin.nbt;

import java.io.DataInput;
import java.io.DataInputStream;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Paper: sources/net/minecraft/nbt/NbtIo.java.patch */
@Mixin(NbtIo.class)
public abstract class NbtIoMixin {
    // Spigot start
    @ModifyVariable(method = "read(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;", at = @At("HEAD"), argsOnly = true)
    private static DataInput spigot$limitStream(DataInput input, DataInput input0, NbtAccounter accounter) {
        if (input instanceof io.netty.buffer.ByteBufInputStream byteBufInputStream) {
            input = new DataInputStream(new org.spigotmc.LimitStream(byteBufInputStream, accounter));
        }
        return input;
    }
    // Spigot end
}
