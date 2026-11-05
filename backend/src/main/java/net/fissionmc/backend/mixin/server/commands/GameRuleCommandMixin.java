package net.fissionmc.backend.mixin.server.commands;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.GameRuleCommand;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/server/commands/GameRuleCommand.java.patch (per-world game rules not ported, event only) */
@Mixin(GameRuleCommand.class)
public abstract class GameRuleCommandMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @WrapOperation(method = "setRule", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gamerules/GameRules;set(Lnet/minecraft/world/level/gamerules/GameRule;Ljava/lang/Object;Lnet/minecraft/server/MinecraftServer;)V"))
    private static void paper$gameRuleEvent(final GameRules gameRules, final GameRule gameRule, Object value, final MinecraftServer server, final Operation<Void> original, @Local(ordinal = 0) final CommandSourceStack source) {
        value = org.bukkit.craftbukkit.event.CraftEventFactory.handleGameRuleSet(gameRule, value, source.getLevel(), source.getBukkitSender()).value(); // Paper - per-world game rules and event
        original.call(gameRules, gameRule, value, server);
    }
}
