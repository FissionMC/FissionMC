package net.fissionmc.backend.mixin.server.permissions;

import com.mojang.brigadier.tree.CommandNode;
import net.fissionmc.backend.commands.VanillaCommandPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionProviderCheck;
import net.minecraft.server.permissions.PermissionSetSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/server/permissions/PermissionProviderCheck.java.patch (Vanilla Command permission checking).
 * The {@code vanillaNode} record component is kept in {@link VanillaCommandPermissions} instead; the test() hunk is this injection.
 */
@Mixin(PermissionProviderCheck.class)
public abstract class PermissionProviderCheckMixin {
    @Shadow
    public abstract PermissionCheck test();

    @Inject(method = "test(Lnet/minecraft/server/permissions/PermissionSetSupplier;)Z", at = @At("HEAD"), cancellable = true)
    private void fission$vanillaCommandPermission(final PermissionSetSupplier t, final CallbackInfoReturnable<Boolean> cir) {
        final CommandNode<CommandSourceStack> currentCommand = VanillaCommandPermissions.nodeOf((PermissionProviderCheck<?>) (Object) this);
        if (currentCommand != null && t instanceof CommandSourceStack commandSourceStack && this.test() instanceof PermissionCheck.Require req) {
            cir.setReturnValue(commandSourceStack.hasPermission(req.permission(), org.bukkit.craftbukkit.command.VanillaCommandWrapper.getPermission(currentCommand)));
        }
    }
}
