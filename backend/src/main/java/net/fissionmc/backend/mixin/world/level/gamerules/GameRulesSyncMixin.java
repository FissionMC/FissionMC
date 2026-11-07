package net.fissionmc.backend.mixin.world.level.gamerules;

import net.fissionmc.backend.relay.WorldSettingsBackend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC world settings sync: every game rule change (/gamerule, plugins, the API) ends in GameRules.set; the master owns the rules. New file. */
@Mixin(GameRules.class)
public abstract class GameRulesSyncMixin {
    @Inject(method = "set", at = @At("TAIL"))
    private <T> void fission$ruleSet(GameRule<T> gameRule, T value, MinecraftServer server, CallbackInfo ci) {
        WorldSettingsBackend.onRuleSet((GameRules) (Object) this, gameRule, value);
    }
}
