package net.fissionmc.backend.mixin.network.protocol.common.custom;

import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/network/protocol/common/custom/DiscardedPayload.java.patch
 * Paper adds the record component {@code byte[] data}. Mixin cannot change record components, so it is a field with the
 * accessor {@link #data()}, set by {@link #create} (stand-in for Paper's canonical constructor; ported call sites use it).
 * TODO(behaviour): codec() must read the payload bytes into data (instead of skipping them) and write them back.
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
}
