package net.fissionmc.backend.mixin.world.entity.npc.villager;

import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/npc/villager/AbstractVillager.java.patch */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow protected @Nullable MerchantOffers offers;

    @Shadow protected abstract void updateTrades(ServerLevel level);
    @Shadow protected abstract void rewardTradeXp(MerchantOffer offer);
    @Shadow public abstract void notifyTrade(MerchantOffer offer);

    @Shadow
    static void addOffersFromItemListings(final LootContext lootContext, final MerchantOffers merchantOffers, final HolderSet<VillagerTrade> potentialOffers, final int numberOfOffers) {}

    @Shadow
    private static void addOffersFromItemListingsWithoutDuplicates(final LootContext lootContext, final MerchantOffers merchantOffers, final HolderSet<VillagerTrade> potentialOffers, final int numberOfOffers) {}

    // TODO(behaviour): inventory gets the CraftAbstractVillager holder; notifyTrade no longer increases uses/rewards xp
    //  (moved to processTrade); vanilla 3-arg addOffersFromTradeSet should delegate with -1; offers go through
    //  VillagerAcquireTradeEvent (tryAcquireTrade).

    // CraftBukkit start
    public org.bukkit.craftbukkit.inventory.CraftMerchant getCraftMerchant() {
        return (org.bukkit.craftbukkit.entity.CraftAbstractVillager) ((AbstractVillager) (Object) this).getBukkitEntity();
    }
    // CraftBukkit end

    // Paper start - Villager#resetOffers
    public void resetOffers() {
        this.offers = new MerchantOffers();
        this.updateTrades((ServerLevel) ((AbstractVillager) (Object) this).level());
    }
    // Paper end - Villager#resetOffers

    // Paper start - Add PlayerTradeEvent and PlayerPurchaseEvent
    public void processTrade(MerchantOffer offer, io.papermc.paper.event.player.@Nullable PlayerPurchaseEvent event) { // The MerchantRecipe passed in here is the one set by the PlayerPurchaseEvent
        if (event == null || event.willIncreaseTradeUses()) {
            offer.increaseUses();
        }
        if (event == null || event.isRewardingExp()) {
            this.rewardTradeXp(offer);
        }
        this.notifyTrade(offer);
    }
    // Paper end - Add PlayerTradeEvent and PlayerPurchaseEvent

    // Paper start - More vanilla friendly methods to update trades
    protected void addOffersFromTradeSet(final ServerLevel level, final MerchantOffers offers, final ResourceKey<TradeSet> resourceKey, int offerCount) {
        // Paper end - More vanilla friendly methods to update trades
        AbstractVillager self = (AbstractVillager) (Object) this;
        Optional<TradeSet> tradeSetOpt = self.registryAccess().lookupOrThrow(Registries.TRADE_SET).getOptional(resourceKey);
        if (tradeSetOpt.isEmpty()) {
            LOGGER.debug("Missing expected trade set {}", resourceKey);
        } else {
            TradeSet tradeSet = tradeSetOpt.get();
            LootContext lootContext = new LootContext.Builder(
                    new LootParams.Builder(level)
                        .withParameter(LootContextParams.ORIGIN, self.position())
                        .withParameter(LootContextParams.THIS_ENTITY, self)
                        .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                        .create(LootContextParamSets.VILLAGER_TRADE)
                )
                .create(tradeSet.randomSequence());
            int numberOfOffers = offerCount != -1 ? offerCount : tradeSet.calculateNumberOfTrades(lootContext); // Paper - More vanilla friendly methods to update trades
            if (tradeSet.allowDuplicates()) {
                addOffersFromItemListings(lootContext, offers, tradeSet.trades(), numberOfOffers); // TODO(behaviour): Paper - pass villager (tryAcquireTrade)
            } else {
                addOffersFromItemListingsWithoutDuplicates(lootContext, offers, tradeSet.trades(), numberOfOffers); // TODO(behaviour): Paper - pass villager (tryAcquireTrade)
            }
        }
    }
}
