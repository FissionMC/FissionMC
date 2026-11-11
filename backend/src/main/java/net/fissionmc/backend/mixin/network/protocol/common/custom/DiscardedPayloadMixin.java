package net.fissionmc.backend.mixin.network.protocol.common.custom;

import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/network/protocol/common/custom/DiscardedPayload.java.patch
 * Paper adds the record component {@code byte[] data}. Mixin cannot change record components, so it is a field with the
 * accessor {@link #data()}, set by {@link #create} (stand-in for Paper's canonical constructor; ported call sites use it).
 * codec() is replaced (reads the payload bytes into data instead of skipping them, writes them back).
 */
@Mixin(DiscardedPayload.class)
public abstract class DiscardedPayloadMixin {
    private byte[] data = new byte[0];

    public byte[] data() {
        return this.data;
    }

    // Surface: public static. Mixin requires private statics; the loader widens it.
    private static DiscardedPayload create(Identifier id, byte[] data) {
        DiscardedPayload payload = new DiscardedPayload(id);
        ((DiscardedPayloadMixin) (Object) payload).data = data;
        return payload;
    }

    @Inject(method = "codec", at = @At("HEAD"), cancellable = true)
    private static <T extends FriendlyByteBuf> void paper$codec(final Identifier id, final int maxPayloadSize, final CallbackInfoReturnable<StreamCodec<T, DiscardedPayload>> cir) {
        cir.setReturnValue(CustomPacketPayload.<T, DiscardedPayload>codec((payload, buf) -> {
            // Paper start
            // Always write data
            buf.writeBytes(payload.data());
        }, buf -> {
            int length = buf.readableBytes();
            if (length >= 0 && length <= maxPayloadSize) {
                final byte[] data = new byte[length];
                buf.readBytes(data);
                return create(id, data);
                // Paper end
            } else {
                throw new IllegalArgumentException("Payload may not be larger than " + maxPayloadSize + " bytes");
            }
        }));
    }
}
