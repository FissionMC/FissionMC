package net.fissionmc.backend.mixin.world.entity.npc.wanderingtrader;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/npc/wanderingtrader/WanderingTrader.java.patch */
@Mixin(WanderingTrader.class)
public abstract class WanderingTraderMixin {
    // Paper start - Add more WanderingTrader API
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private boolean canDrinkPotion = true;
    private boolean canDrinkMilk = true;
    // Paper end - Add more WanderingTrader API
    // TODO(behaviour): registerGoals' UseItemGoal conditions check canDrinkPotion/canDrinkMilk.
}
