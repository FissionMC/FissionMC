package net.fissionmc.backend.mixin.network.protocol.game;

import it.unimi.dsi.fastutil.shorts.ShortSet;
import it.unimi.dsi.fastutil.shorts.ShortSets;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket.java.patch
 * Mixin cannot add constructors: the CraftBukkit/Paper constructors are static factories `create(...)`; ported call sites
 * use them. Each builds an empty packet with the vanilla constructor (empty set, so the null section is never read) and
 * then assigns the fields like Paper's constructor body.
 */
@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public abstract class ClientboundSectionBlocksUpdatePacketMixin {
    @Shadow @Final @Mutable private SectionPos sectionPos;
    @Shadow @Final @Mutable private short[] positions;
    @Shadow @Final @Mutable private BlockState[] states;

    // TODO(behaviour): SPIGOT-6076 null-section guard in the vanilla constructor

    // CraftBukkit start - Add constructor
    // Surface: public static. Mixin requires private statics; the loader widens them.
    private static ClientboundSectionBlocksUpdatePacket create(SectionPos sectionposition, ShortSet shortset, BlockState[] states) {
        ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(sectionposition, ShortSets.EMPTY_SET, null);
        ClientboundSectionBlocksUpdatePacketMixin self = (ClientboundSectionBlocksUpdatePacketMixin) (Object) packet;
        self.sectionPos = sectionposition;
        self.positions = shortset.toShortArray();
        self.states = states;
        return packet;
    }
    // CraftBukkit end
    // Paper start - Multi Block Change API
    private static ClientboundSectionBlocksUpdatePacket create(SectionPos sectionPos, it.unimi.dsi.fastutil.shorts.Short2ObjectMap<BlockState> blockChanges) {
        ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(sectionPos, ShortSets.EMPTY_SET, null);
        ClientboundSectionBlocksUpdatePacketMixin self = (ClientboundSectionBlocksUpdatePacketMixin) (Object) packet;
        self.sectionPos = sectionPos;
        self.positions = blockChanges.keySet().toShortArray();
        self.states = blockChanges.values().toArray(new BlockState[0]);
        return packet;
    }
    // Paper end - Multi Block Change API
}
