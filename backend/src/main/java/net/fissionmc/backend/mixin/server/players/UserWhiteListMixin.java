package net.fissionmc.backend.mixin.server.players;

import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/server/players/UserWhiteList.java.patch (WhitelistStateUpdateEvent). */
@Mixin(UserWhiteList.class)
public abstract class UserWhiteListMixin {
    // Paper start - Add whitelist events
    @Inject(method = "add(Lnet/minecraft/server/players/UserWhiteListEntry;)Z", at = @At("HEAD"), cancellable = true)
    private void fission$addEvent(final UserWhiteListEntry infos, final CallbackInfoReturnable<Boolean> cir) {
        if (!new io.papermc.paper.event.server.WhitelistStateUpdateEvent(com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitCopy(infos.getUser().toUncompletedGameProfile()), io.papermc.paper.event.server.WhitelistStateUpdateEvent.WhitelistStatus.ADDED).callEvent()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "remove(Lnet/minecraft/server/players/NameAndId;)Z", at = @At("HEAD"), cancellable = true)
    private void fission$removeEvent(final NameAndId user, final CallbackInfoReturnable<Boolean> cir) {
        if (!new io.papermc.paper.event.server.WhitelistStateUpdateEvent(com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitCopy(user.toUncompletedGameProfile()), io.papermc.paper.event.server.WhitelistStateUpdateEvent.WhitelistStatus.REMOVED).callEvent()) {
            cir.setReturnValue(false);
        }
    }
    // Paper end - Add whitelist events
}
