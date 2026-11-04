package net.fissionmc.backend.mixin.server;

import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/ReloadableServerResources.java.patch */
@Mixin(ReloadableServerResources.class)
public abstract class ReloadableServerResourcesMixin {
    @Shadow @Final private Commands commands;

    // after `this.commands = new Commands(...)`
    @Inject(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/server/ReloadableServerResources;commands:Lnet/minecraft/commands/Commands;", opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void paper$setDispatcher(
        final ReloadableServerRegistries.LoadResult loadingContext, final FeatureFlagSet enabledFeatures, final Commands.CommandSelection commandSelection,
        final List<Registry.PendingTags<?>> postponedTags, final PermissionSet functionCompilationPermissions,
        final List<DataComponentInitializers.PendingComponents<?>> newComponents, final CallbackInfo ci
    ) {
        // TODO(behaviour): Paper constructs Commands(commandSelection, context, true) ("use modern alias registration"); that constructor is not ported
        io.papermc.paper.command.brigadier.PaperCommands.INSTANCE.setDispatcher(this.commands, CommandBuildContext.simple(loadingContext.lookupWithUpdatedTags(), enabledFeatures)); // Paper - Brigadier Command API
        io.papermc.paper.command.PaperCommands.registerCommands(); // Paper
    }

    // TODO(behaviour): loadResources hunk - LifecycleEvents.COMMANDS for bootstrap contexts (BootstrapContext, INITIAL/RELOAD)
}
