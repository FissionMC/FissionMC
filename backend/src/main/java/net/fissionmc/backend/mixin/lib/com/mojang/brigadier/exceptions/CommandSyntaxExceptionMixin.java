package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.exceptions;

import com.mojang.brigadier.Message;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/com/mojang/brigadier/exceptions/CommandSyntaxException.java.patch */
@Mixin(CommandSyntaxException.class)
public abstract class CommandSyntaxExceptionMixin implements net.kyori.adventure.util.ComponentMessageThrowable { // Paper - Brigadier API
    @Shadow @Final private Message message;

    // Paper start - Brigadier API
    @Override
    public net.kyori.adventure.text.@org.jspecify.annotations.Nullable Component componentMessage() {
        return io.papermc.paper.brigadier.PaperBrigadier.componentFromMessage(this.message);
    }
    // Paper end - Brigadier API
}
