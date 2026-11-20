package net.fissionmc.backend.support;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/** Shared by the tame mixins: Paper puts `!callEntityTameEvent(...).isCancelled()` behind the random tame roll; here the roll is rewritten instead. */
public final class TameEvents {
    private TameEvents() {
    }

    /** @param roll result of random.nextInt(n) where 0 means "tamed"; returns a non-zero value when EntityTameEvent is cancelled */
    public static int roll(final int roll, final Mob mob, final Player player) {
        if (roll == 0 && org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTameEvent(mob, player).isCancelled()) {
            return 1;
        }
        return roll;
    }
}
