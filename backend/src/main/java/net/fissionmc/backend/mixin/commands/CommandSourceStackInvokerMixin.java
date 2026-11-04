package net.fissionmc.backend.mixin.commands;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.TaskChainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invoker for CommandSourceStack's private full constructor, used by {@link CommandSourceStackMixin#withLocation}. */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackInvokerMixin {
    @Invoker("<init>")
    static CommandSourceStack fission$create(
        final CommandSource source,
        final Vec3 position,
        final Vec2 rotation,
        final ServerLevel level,
        final PermissionSet permissions,
        final CommandSourceStack.NamesProvider namesProvider,
        final MinecraftServer server,
        final Entity entity,
        final boolean silent,
        final CommandResultCallback resultCallback,
        final EntityAnchorArgument.Anchor anchor,
        final CommandSigningContext signingContext,
        final TaskChainer chatMessageChainer
    ) {
        throw new AssertionError();
    }
}
