package net.fissionmc.backend.mixin.world.entity.npc.villager;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/npc/villager/Villager.java.patch */
@Mixin(Villager.class)
public abstract class VillagerMixin extends AbstractVillager {
    @Shadow private void increaseMerchantCareer(final ServerLevel level) {} // Surface: public (Paper - public)
    @Shadow private void updateSpecialPrices(final Player player) {}

    protected VillagerMixin(final EntityType<? extends AbstractVillager> type, final Level level) {
        super(type, level);
    }

    // TODO(behaviour): vanilla updateTrades(ServerLevel) should delegate to updateTrades(level, -1).

    // Paper start - More vanilla friendly methods to update trades
    public boolean updateTrades(final ServerLevel level, int amount) {
        // Paper end - More vanilla friendly methods to update trades
        VillagerData data = ((Villager) (Object) this).getVillagerData();
        VillagerProfession profession = data.profession().value();
        ResourceKey<TradeSet> trades = profession.getTrades(data.level());
        if (trades != null) {
            this.addOffersFromTradeSet(level, this.getOffers(), trades, amount); // Paper - More vanilla friendly methods to update trades
            if (SharedConstants.DEBUG_UNLOCK_ALL_TRADES && data.level() < 5) {
                this.increaseMerchantCareer(level);
            }

            Player tradingPlayer = this.getTradingPlayer();
            if (tradingPlayer != null) {
                this.updateSpecialPrices(tradingPlayer);
            }
            return true; // Paper - More vanilla friendly methods to update trades
        }
        return false; // Paper - More vanilla friendly methods to update trades
    }
}
