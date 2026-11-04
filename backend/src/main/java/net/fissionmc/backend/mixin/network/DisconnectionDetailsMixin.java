package net.fissionmc.backend.mixin.network;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/network/DisconnectionDetails.java.patch
 * Paper adds two record components (quitMessage, disconnectionReason). Mixin cannot change a record's components, so they
 * are plain fields with accessor methods, set by the {@link #create} factory (stand-in for Paper's canonical constructor).
 * They do not take part in equals/hashCode/toString.
 */
@Mixin(DisconnectionDetails.class)
public abstract class DisconnectionDetailsMixin {
    private Optional<Component> quitMessage = Optional.empty();
    private Optional<io.papermc.paper.connection.DisconnectionReason> disconnectionReason = Optional.empty();

    public Optional<Component> quitMessage() {
        return this.quitMessage;
    }

    public Optional<io.papermc.paper.connection.DisconnectionReason> disconnectionReason() {
        return this.disconnectionReason;
    }

    // Surface: public static. Mixin requires private statics; the loader widens it.
    private static DisconnectionDetails create(Component reason, Optional<Path> report, Optional<URI> bugReportLink, Optional<Component> quitMessage, Optional<io.papermc.paper.connection.DisconnectionReason> disconnectionReason) {
        DisconnectionDetails details = new DisconnectionDetails(reason, report, bugReportLink);
        DisconnectionDetailsMixin self = (DisconnectionDetailsMixin) (Object) details;
        self.quitMessage = quitMessage;
        self.disconnectionReason = disconnectionReason;
        return details;
    }
}
