package net.fissionmc.backend.mixin.world.level.gamerules;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/gamerules/GameRules.java.patch */
@Mixin(GameRules.class)
public abstract class GameRulesMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private GameRuleMap rules;

    public <T> void set(final GameRule<T> gameRule, final T value, final @Nullable ServerLevel level) { // Paper - per-world game rules
        if (!this.rules.has(gameRule)) {
            LOGGER.warn("Tried to set invalid game rule '{}' to value '{}'", gameRule.getIdentifierWithFallback(), value);
        } else {
            this.rules.set(gameRule, value);
            // Paper start - per-world game rules
            if (level != null) {
                // TODO(behaviour): Paper calls the per-level MinecraftServer#onGameRuleChanged(ServerLevel, GameRule, T) overload
                level.getServer().onGameRuleChanged(gameRule, value);
                // Paper end - per-world game rules
            }
        }
    }
}
