package net.fissionmc.backend.mixin.network.protocol.login;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket.java.patch */
@Mixin(ServerboundCustomQueryAnswerPacket.class)
public abstract class ServerboundCustomQueryAnswerPacketMixin {
    @Shadow @Final private static int MAX_PAYLOAD_SIZE;

    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void paper$readPayload(final int transactionId, final FriendlyByteBuf input, final CallbackInfoReturnable<CustomQueryAnswerPayload> cir) {
        // Paper start - MC Utils - default query payloads
        FriendlyByteBuf buf = input.readNullable((buf2) -> {
            int readableBytes = buf2.readableBytes();
            if (readableBytes >= 0 && readableBytes <= MAX_PAYLOAD_SIZE) {
                return new FriendlyByteBuf(buf2.readBytes(readableBytes));
            } else {
                throw new IllegalArgumentException("Payload may not be larger than " + MAX_PAYLOAD_SIZE + " bytes");
            }
        });
        cir.setReturnValue(buf == null ? null : new net.fissionmc.backend.network.QueryAnswerPayload(buf)); // FissionMC: nested QueryAnswerPayload lives in backend
        // Paper end - MC Utils - default query payloads
    }
}
