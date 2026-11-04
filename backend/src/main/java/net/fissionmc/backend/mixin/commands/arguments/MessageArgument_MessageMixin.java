package net.fissionmc.backend.mixin.commands.arguments;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** FissionMC: access for MessageArgumentMixin#resolveChatMessage(Message, ...). */
@Mixin(MessageArgument.Message.class)
public interface MessageArgument_MessageMixin {
    @Invoker("resolveComponent")
    Component fission$resolveComponent(CommandSourceStack source) throws CommandSyntaxException;
}
