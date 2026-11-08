package net.fissionmc.backend.mixin.world.inventory;

import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/inventory/MerchantResultSlot.java.patch (PlayerTradeEvent / PlayerPurchaseEvent). onTake is replaced as a whole. */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {
    @Shadow @Final private MerchantContainer slots;
    @Shadow @Final private Merchant merchant;

    @Shadow protected abstract void checkTakeAchievements(ItemStack carried);

    @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
    private void fission$onTake(final Player player, final ItemStack carried, final CallbackInfo ci) {
        ci.cancel();
        // this.checkTakeAchievements(carried); // Paper - Add PlayerTradeEvent and PlayerPurchaseEvent; move to after event is called and not cancelled
        MerchantOffer offer = this.slots.getActiveOffer();
        // Paper start - Add PlayerTradeEvent and PlayerPurchaseEvent
        io.papermc.paper.event.player.PlayerPurchaseEvent event = null;
        if (offer != null && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (this.merchant instanceof net.minecraft.world.entity.npc.villager.AbstractVillager abstractVillager) {
                event = new io.papermc.paper.event.player.PlayerTradeEvent(serverPlayer.getBukkitEntity(), (org.bukkit.entity.AbstractVillager) abstractVillager.getBukkitEntity(), offer.asBukkit(), true, true);
            } else if (this.merchant instanceof org.bukkit.craftbukkit.inventory.CraftMerchantCustom.MinecraftMerchant minecraftMerchant) {
                event = new io.papermc.paper.event.player.PlayerPurchaseEvent(serverPlayer.getBukkitEntity(), minecraftMerchant.getCraftMerchant(), offer.asBukkit(), false, true);
            }
            if (event != null) {
                if (!event.callEvent()) {
                    carried.setCount(0);
                    player.containerMenu.sendAllDataToRemote();
                    final int level = merchant instanceof net.minecraft.world.entity.npc.villager.Villager villager ? villager.getVillagerData().level() : 1;
                    serverPlayer.sendMerchantOffers(player.containerMenu.containerId, merchant.getOffers(), level, merchant.getVillagerXp(), merchant.showProgressBar(), merchant.canRestock());
                    return;
                }
                offer = org.bukkit.craftbukkit.inventory.CraftMerchantRecipe.fromBukkit(event.getTrade()).toMinecraft();
            }
        }
        this.checkTakeAchievements(carried);
        // Paper end - Add PlayerTradeEvent and PlayerPurchaseEvent
        if (offer != null) {
            final ItemStack buyA = this.slots.getItem(0);
            final ItemStack buyB = this.slots.getItem(1);
            if (offer.take(buyA, buyB) || offer.take(buyB, buyA)) {
                this.merchant.processTrade(offer, event); // Paper - Add PlayerTradeEvent and PlayerPurchaseEvent
                player.awardStat(Stats.TRADED_WITH_VILLAGER);
                this.slots.setItem(0, buyA);
                this.slots.setItem(1, buyB);
            }

            this.merchant.overrideXp(this.merchant.getVillagerXp() + offer.getXp());
        }
    }
}
