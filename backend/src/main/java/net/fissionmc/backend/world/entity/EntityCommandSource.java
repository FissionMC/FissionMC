package net.fissionmc.backend.world.entity;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * CraftBukkit's anonymous Entity.commandSource (paper-server/patches/sources/net/minecraft/world/entity/Entity.java.patch),
 * used by Entity#createCommandSourceStackForNameResolution so command senders resolve to the Bukkit entity.
 */
public final class EntityCommandSource implements CommandSource {
    private final Entity entity;

    public EntityCommandSource(final Entity entity) {
        this.entity = entity;
    }

    @Override
    public void sendSystemMessage(Component message) {
    }

    @Override
    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
        return this.entity.getBukkitEntity();
    }

    @Override
    public boolean acceptsSuccess() {
        return ((ServerLevel) this.entity.level()).getGameRules().get(net.minecraft.world.level.gamerules.GameRules.COMMAND_BLOCK_OUTPUT);
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return true;
    }
}
