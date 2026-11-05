package net.fissionmc.backend.mixin.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/server/PlayerAdvancements.java.patch
 * done: PlayerAdvancementCriterionGrantEvent, PlayerAdvancementDoneEvent (Adventure message, fired after rewards instead of before), TreeSet ordering not ported.
 * left: SpigotConfig.disableAdvancementSaving, non-minecraft namespace skipping on save, pretty printing (cosmetic).
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow private ServerPlayer player;
    @Shadow @org.spongepowered.asm.mixin.Final private PlayerList playerList;

    // Paper start - Add PlayerAdvancementCriterionGrantEvent
    @WrapOperation(method = "award", at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/AdvancementProgress;grantProgress(Ljava/lang/String;)Z"))
    private boolean paper$criterionGrantEvent(final AdvancementProgress progress, final String criterion, final Operation<Boolean> original, @Local(argsOnly = true) final AdvancementHolder holder) {
        final boolean granted = original.call(progress, criterion);
        if (granted && !new com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent(this.player.getBukkitEntity(), holder.toBukkit(), criterion).callEvent()) {
            progress.revokeProgress(criterion);
            return false;
        }
        return granted;
    }
    // Paper end - Add PlayerAdvancementCriterionGrantEvent

    // Paper start - Add Adventure message to PlayerAdvancementDoneEvent
    @WrapOperation(method = "award", at = @At(value = "INVOKE", target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"))
    private void paper$advancementDoneEvent(final Optional<DisplayInfo> display, final Consumer<DisplayInfo> consumer, final Operation<Void> original, @Local(argsOnly = true) final AdvancementHolder holder) {
        final net.kyori.adventure.text.Component message = holder.value().display()
            .filter(DisplayInfo::announceToChat)
            .map(info -> io.papermc.paper.adventure.PaperAdventure.asAdventure(info.type().createAnnouncement(holder, this.player)))
            .orElse(null);
        final org.bukkit.event.player.PlayerAdvancementDoneEvent event = new org.bukkit.event.player.PlayerAdvancementDoneEvent(this.player.getBukkitEntity(), holder.toBukkit(), message);
        this.player.level().getCraftServer().getPluginManager().callEvent(event); // CraftBukkit
        if (display.isPresent() && event.message() != null && this.player.level().getGameRules().get(GameRules.SHOW_ADVANCEMENT_MESSAGES)) {
            this.playerList.broadcastSystemMessage(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.message()), false);
        }
    }
    // Paper end
}
