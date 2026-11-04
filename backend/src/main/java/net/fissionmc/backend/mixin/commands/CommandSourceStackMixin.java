package net.fissionmc.backend.mixin.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.TaskChainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/commands/CommandSourceStack.java.patch */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin implements io.papermc.paper.command.brigadier.PaperCommandSourceStack { // Paper - Brigadier API
    @Shadow @Final private CommandSource source; // public via paper.at
    @Shadow @Final private PermissionSet permissions;
    @Shadow @Final private CommandSourceStack.NamesProvider namesProvider;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private boolean silent;
    @Shadow @Final private Entity entity;
    @Shadow @Final private CommandResultCallback resultCallback;
    @Shadow @Final private EntityAnchorArgument.Anchor anchor;
    @Shadow @Final private CommandSigningContext signingContext;
    @Shadow @Final private TaskChainer chatMessageChainer;

    @Shadow public abstract MinecraftServer getServer();

    // Surface: public. Mixin requires non-public fields; the loader widens it.
    private boolean bypassSelectorPermissions = false; // Paper - add bypass for selector permissions

    // Paper start - Expose 'with' functions from the CommandSourceStack
    @Override
    public CommandSourceStack withLocation(org.bukkit.Location location) {
        return this.getLocation().equals(location)
            ? (CommandSourceStack) (Object) this
            : CommandSourceStackInvokerMixin.fission$create(
            this.source,
            new Vec3(location.x(), location.y(), location.z()),
            new Vec2(location.getPitch(), location.getYaw()),
            ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle(),
            this.permissions,
            this.namesProvider,
            this.server,
            this.entity,
            this.silent,
            this.resultCallback,
            this.anchor,
            this.signingContext,
            this.chatMessageChainer
        );
    }
    // Paper end - Expose 'with' functions from the CommandSourceStack

    // Paper start - Fix permission levels for command blocks
    private boolean forceRespectPermissionLevel() {
        return this.source == CommandSource.NULL || (this.source instanceof final net.minecraft.world.level.BaseCommandBlock commandBlock && commandBlock.getLevel().paperConfig().commandBlocks.forceFollowPermLevel);
    }
    // Paper end - Fix permission levels for command blocks

    // CraftBukkit start
    public boolean hasPermission(net.minecraft.server.permissions.Permission permission, String bukkitPermission) {
        // Paper start - Fix permission levels for command blocks
        final java.util.function.BooleanSupplier hasBukkitPerm = () -> this.source == CommandSource.NULL /*treat NULL as having all bukkit perms*/ || this.getBukkitSender().hasPermission(bukkitPermission); // lazily check bukkit perms to the benefit of custom permission setups
        // if the server is null, we must check the vanilla perm level system
        // if ignoreVanillaPermissions is true, we can skip vanilla perms and just run the bukkit perm check
        //noinspection ConstantValue
        if (this.getServer() == null || !this.getServer().server.ignoreVanillaPermissions) { // server & level are null for command function loading
            final boolean hasPermLevel = this.permissions.hasPermission(permission);
            if (this.forceRespectPermissionLevel()) { // NULL CommandSource and command blocks (if setting is enabled) should always pass the vanilla perm check
                return hasPermLevel && hasBukkitPerm.getAsBoolean();
            } else { // otherwise check vanilla perm first then bukkit perm, matching upstream behavior
                return hasPermLevel || hasBukkitPerm.getAsBoolean();
            }
        }
        return hasBukkitPerm.getAsBoolean();
        // Paper end - Fix permission levels for command blocks
    }
    // CraftBukkit end

    // Paper start - Add UnknownCommandEvent
    public void sendFailure(final Component message, final boolean withStyle) {
        // Paper end - Add UnknownCommandEvent
        if (this.source.acceptsFailure() && !this.silent) {
            this.source.sendSystemMessage(withStyle ? Component.empty().append(message).withStyle(ChatFormatting.RED) : message); // Paper - Add UnknownCommandEvent
        }
    }
    // TODO(behaviour): vanilla sendFailure(Component) should delegate to sendFailure(message, true); Paper also changes
    // broadcastToAdmins (bukkit permission / silentCommandBlocks) and getOnlinePlayerNames (hidden players).

    // Paper start
    @Override
    public CommandSourceStack getHandle() {
        return (CommandSourceStack) (Object) this;
    }
    // Paper end
    // CraftBukkit start
    @Override
    public org.bukkit.command.CommandSender getBukkitSender() {
        return this.source.getBukkitSender((CommandSourceStack) (Object) this);
    }
    // CraftBukkit end
}
