package net.fissionmc.backend.mixin.server.level;

import net.minecraft.server.level.TicketType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/level/TicketType.java.patch */
@Mixin(TicketType.class)
public abstract class TicketTypeMixin {
    @Shadow private static TicketType register(final String name, final long timeout, final @TicketType.Flags int flags) { throw new AssertionError(); }

    private static final TicketType PLUGIN = register("plugin", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION); // CraftBukkit
    private static final TicketType POST_TELEPORT = register("post_teleport", 5L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION); // Paper
    private static final TicketType PLUGIN_TICKET = register("plugin_ticket", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION); // Paper
    private static final TicketType FUTURE_AWAIT = register("future_await", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION); // Paper
    private static final TicketType CHUNK_LOAD = register("chunk_load", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING); // Paper - moonrise

    // Paper start - chunk-gc config
    private static int PLUGIN_TYPE_TIMEOUT = 600;

    // FissionMC: Paper overrides the record accessor timeout(); hasTimeout() then follows it
    @Inject(method = "timeout", at = @At("HEAD"), cancellable = true)
    private void paper$pluginTimeout(CallbackInfoReturnable<Long> cir) {
        if ((Object) this == PLUGIN) {
            cir.setReturnValue((long) PLUGIN_TYPE_TIMEOUT);
        }
    }
    // Paper end - chunk-gc config
    // TODO(behaviour): hasTimeout() reads the field directly in vanilla; Paper makes it call timeout()
}
