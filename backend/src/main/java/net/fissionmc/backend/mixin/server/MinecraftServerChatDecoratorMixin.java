package net.fissionmc.backend.mixin.server;

import io.papermc.paper.adventure.ImprovedChatDecorator;
import net.minecraft.network.chat.ChatDecorator;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: MinecraftServer.java.patch "Adventure": {@code getChatDecorator()} returns {@code new ImprovedChatDecorator(this)}. Vanilla returns
 * {@code ChatDecorator.PLAIN}, whose three-argument {@code decorate} is the port's "Must override this implementation" stub, so EVERY chat
 * message of a player ended in an UnsupportedOperationException and a disconnect ("Internal Exception"). New file, merge-safe.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerChatDecoratorMixin {
    @Unique private ChatDecorator fission$chatDecorator;

    @Inject(method = "getChatDecorator", at = @At("HEAD"), cancellable = true)
    private void fission$improvedChatDecorator(CallbackInfoReturnable<ChatDecorator> cir) {
        if (this.fission$chatDecorator == null) {
            this.fission$chatDecorator = new ImprovedChatDecorator((MinecraftServer) (Object) this);
        }
        cir.setReturnValue(this.fission$chatDecorator);
    }
}
