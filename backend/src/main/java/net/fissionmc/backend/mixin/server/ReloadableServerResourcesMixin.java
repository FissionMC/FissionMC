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

    // Paper start - call commands event for bootstraps: after `new ReloadableServerResources(...)`, before SimpleReloadInstance.create
    // (INITIAL while the server does not exist yet, RELOAD for every datapack reload)
    @Inject(
        method = "lambda$loadResources$2",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance;create(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Z)Lnet/minecraft/server/packs/resources/ReloadInstance;")
    )
    private static void paper$callBootstrapCommandsEvent(final CallbackInfo ci) {
        io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner.INSTANCE.callReloadableRegistrarEvent(
            io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
            io.papermc.paper.command.brigadier.PaperCommands.INSTANCE,
            io.papermc.paper.plugin.bootstrap.BootstrapContext.class,
            net.minecraft.server.MinecraftServer.getServer() == null ? io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.INITIAL : io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.RELOAD
        );
    }
    // Paper end - call commands event
}
