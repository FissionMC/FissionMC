package net.fissionmc.backend.mixin.commands.arguments.selector;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/commands/arguments/selector/EntitySelectorParser.java.patch */
@Mixin(EntitySelectorParser.class)
public abstract class EntitySelectorParserMixin {
    @Shadow private boolean usesSelectors;
    @Shadow public abstract EntitySelector parse() throws CommandSyntaxException;

    private boolean paper$overridePermissions;

    // CraftBukkit start
    public EntitySelector parse(boolean overridePermissions) throws CommandSyntaxException {
        // CraftBukkit end
        // FissionMC: Paper threads the flag into parseSelector(boolean); here it is carried in a field for the duration of the parse
        final boolean previous = this.paper$overridePermissions;
        this.paper$overridePermissions = overridePermissions;
        try {
            return this.parse();
        } finally {
            this.paper$overridePermissions = previous;
        }
    }

    // CraftBukkit - parseSelector(boolean): this.usesSelectors = !overridePermissions;
    @Inject(method = "parseSelector", at = @At("TAIL"))
    private void paper$overridePermissions(CallbackInfo ci) {
        if (this.paper$overridePermissions) {
            this.usesSelectors = false;
        }
    }

    // Paper - Fix EntityArgument permissions: the generic overload `allowSelectors(S)` (the PermissionSetSupplier overload is not patched)
    @Inject(method = "allowSelectors(Ljava/lang/Object;)Z", at = @At("HEAD"), cancellable = true)
    private static void paper$allowSelectors(final Object source, final CallbackInfoReturnable<Boolean> cir) {
        if (source instanceof net.minecraft.commands.CommandSourceStack stack) {
            cir.setReturnValue(stack.bypassSelectorPermissions || stack.hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ENTITY_SELECTORS, "minecraft.command.selector"));
        }
    }
}
