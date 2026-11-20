package net.fissionmc.backend.mixin.commands;

import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/commands/Commands.java.patch (anonymous COMMAND_NODE_INSPECTOR: "restricted api"). */
@Mixin(targets = "net.minecraft.commands.Commands$1")
public abstract class Commands_1Mixin {
    @Inject(method = "isRestricted", at = @At("HEAD"), cancellable = true)
    private void paper$restrictedMarker(final CommandNode<CommandSourceStack> node, final CallbackInfoReturnable<Boolean> cir) {
        if (node.getRequirement() instanceof net.fissionmc.craft.compat.RestrictedMarker marker) {
            cir.setReturnValue(marker.restricted()); // Paper - restricted api
        }
    }
}
