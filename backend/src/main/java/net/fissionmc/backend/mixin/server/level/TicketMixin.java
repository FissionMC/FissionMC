package net.fissionmc.backend.mixin.server.level;

import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/server/level/Ticket.java.patch */
@Mixin(Ticket.class)
public abstract class TicketMixin {
    // Paper start - add identifier
    private Object identifier;

    public Object getIdentifier() {
        return this.identifier;
    }
    // Paper end - add identifier

    // FissionMC: replaces Paper's added Ticket(TicketType, int, Object) constructor
    private static Ticket create(TicketType type, int ticketLevel, Object identifier) {
        final Ticket ticket = new Ticket(type, ticketLevel);
        ((TicketMixin) (Object) ticket).identifier = identifier;
        return ticket;
    }
    // TODO(behaviour): codec / private constructors carry the identifier
}
