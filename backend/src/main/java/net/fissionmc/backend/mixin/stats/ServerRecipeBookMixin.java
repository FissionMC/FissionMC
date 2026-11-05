package net.fissionmc.backend.mixin.stats;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/stats/ServerRecipeBook.java.patch */
@Mixin(ServerRecipeBook.class)
public abstract class ServerRecipeBookMixin {
    private static org.bukkit.event.player.PlayerRecipeDiscoverEvent fission$discoverEvent; // static: read from the static lambda below (main thread only)

    // Paper - PlayerRecipeDiscoverEvent event. The event is fired once the vanilla guards passed ("!known.contains(id) && !isSpecial"):
    // reached through isSpecial(), a cancelled event is reported as "special" so the loop skips the recipe (Paper: continue).
    @ModifyExpressionValue(method = "addRecipes", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/Recipe;isSpecial()Z"))
    private boolean paper$recipeDiscoverEvent(final boolean special, @Local(argsOnly = true) final ServerPlayer player, @Local final RecipeHolder<?> recipe) {
        if (special) {
            return true;
        }
        fission$discoverEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerRecipeListUpdateEvent(player, recipe);
        return fission$discoverEvent.isCancelled();
    }

    // Paper - set notification from the event
    @ModifyExpressionValue(method = "lambda$addRecipes$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/Recipe;showNotification()Z"))
    private static boolean paper$notificationFromEvent(final boolean showNotification) {
        return fission$discoverEvent != null ? fission$discoverEvent.shouldShowNotification() : showNotification;
    }

    // SPIGOT-4478 during PlayerLoginEvent
    @ModifyExpressionValue(method = "addRecipes", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z", ordinal = 0))
    private boolean spigot$noConnectionAdd(final boolean empty, @Local(argsOnly = true) final ServerPlayer player) {
        return empty || player.connection == null;
    }

    @ModifyExpressionValue(method = "removeRecipes", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z", ordinal = 0))
    private boolean spigot$noConnectionRemove(final boolean empty, @Local(argsOnly = true) final ServerPlayer player) {
        return empty || player.connection == null;
    }
}
