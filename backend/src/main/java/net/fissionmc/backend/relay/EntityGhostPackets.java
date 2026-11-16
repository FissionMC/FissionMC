package net.fissionmc.backend.relay;

import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Encodes entities for the master with vanilla's own codecs. Java because nested vanilla classes are not visible from Kotlin. */
public final class EntityGhostPackets {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    private EntityGhostPackets() {}

    /** @return the add entity packet payload starting at the entity id, or null when the entity is not tracked yet */
    public static byte[] add(ServerLevel level, Entity entity) {
        Object tracked = level.getChunkSource().chunkMap.entityMap.get(entity.getId());
        if (tracked == null) return null;
        ServerEntity serverEntity = ((net.minecraft.server.level.ChunkMap.TrackedEntity) tracked).serverEntity;
        var packet = entity.getAddEntityPacket(serverEntity);
        if (!(packet instanceof ClientboundAddEntityPacket add)) return null;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        ClientboundAddEntityPacket.STREAM_CODEC.encode(buf, add);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    /** @return the set entity data payload after the entity id */
    public static byte[] data(MinecraftServer server, Entity entity) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        var values = entity.getEntityData().getNonDefaultValues();
        ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf, new ClientboundSetEntityDataPacket(entity.getId(), values == null ? List.of() : values));
        buf.readVarInt();
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    /** @return the set equipment payload after the entity id, empty when nothing is worn */
    public static byte[] equipment(MinecraftServer server, Entity entity) {
        if (!(entity instanceof LivingEntity living)) return new byte[0];
        List<Pair<EquipmentSlot, ItemStack>> worn = new ArrayList<>();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = living.getItemBySlot(slot);
            if (!stack.isEmpty()) worn.add(Pair.of(slot, stack.copy()));
        }
        if (worn.isEmpty()) return new byte[0];
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        ClientboundSetEquipmentPacket.STREAM_CODEC.encode(buf, new ClientboundSetEquipmentPacket(entity.getId(), worn));
        buf.readVarInt();
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
}
