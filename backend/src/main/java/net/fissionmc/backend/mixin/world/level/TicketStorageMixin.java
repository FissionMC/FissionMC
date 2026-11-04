package net.fissionmc.backend.mixin.world.level;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/TicketStorage.java.patch */
@Mixin(TicketStorage.class)
public abstract class TicketStorageMixin {
    @Shadow public abstract boolean addTicket(long key, Ticket ticket);
    @Shadow public abstract boolean removeTicket(long key, Ticket ticket);
    @Shadow public abstract void removeTicketIf(TicketStorage.TicketPredicate predicate, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.List<Ticket>> removedTickets);

    // TODO(behaviour): ticket equality includes the identifier; deactivateTicketsOnClosing keeps CHUNK_LOAD/FUTURE_AWAIT

    // Paper start
    public boolean addPluginRegionTicket(final ChunkPos pos, final org.bukkit.plugin.Plugin value) {
        // Keep inline with force loading
        return addTicket(pos.pack(), Ticket.create(TicketType.PLUGIN_TICKET, ChunkMap.FORCED_TICKET_LEVEL, value));
    }

    public boolean removePluginRegionTicket(final ChunkPos pos, final org.bukkit.plugin.Plugin value) {
        // Keep inline with force loading
        return removeTicket(pos.pack(), Ticket.create(TicketType.PLUGIN_TICKET, ChunkMap.FORCED_TICKET_LEVEL, value));
    }

    public void removeAllPluginRegionTickets(TicketType ticketType, int ticketLevel, org.bukkit.plugin.Plugin ticketIdentifier) {
        removeTicketIf((ticket, _) -> ticket.getType() == ticketType && ticket.getTicketLevel() == ticketLevel && ticket.getIdentifier() == ticketIdentifier, null);
    }
    // Paper end
}
