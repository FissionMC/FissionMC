package net.fissionmc.backend.mixin.commands.arguments;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/commands/arguments/MessageArgument.java.patch */
@Mixin(MessageArgument.class)
public abstract class MessageArgumentMixin {
    @Shadow private static void resolveSignedMessage(final Consumer<PlayerChatMessage> task, final CommandSourceStack sender, final PlayerChatMessage signedArgument) {}
    @Shadow private static void resolveDisguisedMessage(final Consumer<PlayerChatMessage> task, final CommandSourceStack sender, final PlayerChatMessage argument) {}

    // Paper start - brig message argument support
    // TODO(behaviour): vanilla resolveChatMessage(context, name, task) delegates here; async chat decoration hunks
    private static void resolveChatMessage(final MessageArgument.Message message, final CommandContext<CommandSourceStack> context, final String name, final Consumer<PlayerChatMessage> task) throws CommandSyntaxException {
        // Paper end - brig message argument support
        CommandSourceStack sender = context.getSource();
        Component formatted = ((MessageArgument_MessageMixin) (Object) message).fission$resolveComponent(sender);
        CommandSigningContext signingContext = sender.getSigningContext();
        PlayerChatMessage signedArgument = signingContext.getArgument(name);
        if (signedArgument != null) {
            resolveSignedMessage(task, sender, signedArgument.withUnsignedContent(formatted));
        } else {
            resolveDisguisedMessage(task, sender, PlayerChatMessage.system(message.text()).withUnsignedContent(formatted));
        }
    }
}
