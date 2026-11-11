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

    // Paper start - Add EntityZapEvent (a cancelled event cancels the conversion: vanilla then runs super.thunderHit), LIGHTNING reasons
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "thunderHit", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/villager/Villager;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private net.minecraft.world.entity.Mob paper$zapEvent(final net.minecraft.world.entity.npc.villager.Villager self, final net.minecraft.world.entity.EntityType<?> type, final net.minecraft.world.entity.ConversionParams params, final net.minecraft.world.entity.ConversionParams.AfterConversion afterConversion, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.entity.Mob> original,
                                   @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final net.minecraft.world.entity.LightningBolt lightningBolt) {
        final net.minecraft.world.entity.ConversionParams.AfterConversion<net.minecraft.world.entity.Mob> wrapped = w -> {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityZapEvent(self, lightningBolt, w).isCancelled()) {
                net.fissionmc.backend.blocks.PaperContext.CONVERSION_CANCELLED.set(Boolean.TRUE);
                return;
            }
            afterConversion.finalizeConversion(w);
        };
        return net.fissionmc.backend.blocks.PaperContext.convert(self, type, params, wrapped, org.bukkit.event.entity.EntityTransformEvent.TransformReason.LIGHTNING, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.LIGHTNING); // CraftBukkit
    }
    // Paper end - Add EntityZapEvent

    // CraftBukkit start - VillagerReplenishTradeEvent (restock and catchUpDemand)
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = {"restock", "catchUpDemand"}, at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/item/trading/MerchantOffer;resetUses()V"))
    private void fission$replenishTradeEvent(final net.minecraft.world.item.trading.MerchantOffer offer, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        org.bukkit.event.entity.VillagerReplenishTradeEvent event = new org.bukkit.event.entity.VillagerReplenishTradeEvent((org.bukkit.entity.Villager) ((net.minecraft.world.entity.npc.villager.Villager) (Object) this).getBukkitEntity(), offer.asBukkit());
        if (event.callEvent()) {
            original.call(offer);
        }
    }
    // CraftBukkit end
}
