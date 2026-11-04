package net.fissionmc.backend.mixin.world.entity.ai.gossip;

import java.util.Map;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.ai.gossip.GossipType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@code GossipContainer.EntityGossips} (paper-server/patches/sources/net/minecraft/world/entity/ai/gossip/GossipContainer.java.patch).
 */
@Mixin(targets = "net.minecraft.world.entity.ai.gossip.GossipContainer$EntityGossips")
public abstract class GossipContainer_EntityGossipsMixin {
    @Shadow @Final private Object2IntMap<GossipType> entries;

    // Paper start - Add villager reputation API
    private static final GossipType[] TYPES = GossipType.values();

    public com.destroystokyo.paper.entity.villager.Reputation asReputation() {
        Map<com.destroystokyo.paper.entity.villager.ReputationType, Integer> map = new java.util.EnumMap<>(com.destroystokyo.paper.entity.villager.ReputationType.class);
        for (Object2IntMap.Entry<GossipType> type : this.entries.object2IntEntrySet()) {
            map.put(toApi(type.getKey()), type.getIntValue());
        }

        return new com.destroystokyo.paper.entity.villager.Reputation(map);
    }

    public void assignFromReputation(com.destroystokyo.paper.entity.villager.Reputation rep) {
        for (GossipType type : TYPES) {
            com.destroystokyo.paper.entity.villager.ReputationType api = toApi(type);

            if (rep.hasReputationSet(api)) {
                int reputation = rep.getReputation(api);
                if (reputation == 0) {
                    this.entries.removeInt(type);
                } else {
                    this.entries.put(type, reputation);
                }
            }
        }
    }

    private static com.destroystokyo.paper.entity.villager.ReputationType toApi(GossipType type) {
        return switch (type) {
            case MAJOR_NEGATIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MAJOR_NEGATIVE;
            case MINOR_NEGATIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MINOR_NEGATIVE;
            case MINOR_POSITIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MINOR_POSITIVE;
            case MAJOR_POSITIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MAJOR_POSITIVE;
            case TRADING -> com.destroystokyo.paper.entity.villager.ReputationType.TRADING;
        };
    }
    // Paper end - Add villager reputation API
}
