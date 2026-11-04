package net.fissionmc.backend.mixin.world.damagesource;

import java.util.List;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link CombatTracker} (paper-server/patches/sources/net/minecraft/world/damagesource/CombatTracker.java.patch).
 */
@Mixin(CombatTracker.class)
public abstract class CombatTrackerMixin {
    // TODO(behaviour): Paper - Fix mem leak (MC-301114); entries is an ArrayListDeque
    @Shadow @Final private List<CombatEntry> entries;
    @Shadow @Final private LivingEntity mob;
    @Shadow private int lastDamageTime;
    @Shadow private int combatStartTime;
    @Shadow private int combatEndTime;
    @Shadow private boolean inCombat;
    @Shadow private boolean takingDamage;

    @Shadow private static boolean shouldEnterCombat(DamageSource source) { throw new AssertionError(); }

    private final io.papermc.paper.world.damagesource.PaperCombatTrackerWrapper paperCombatTracker = new io.papermc.paper.world.damagesource.PaperCombatTrackerWrapper((CombatTracker) (Object) this); // Paper - Combat tracker API

    // TODO(behaviour): vanilla recordDamage(source, damage) should delegate here after building the entry
    public void recordDamageAndCheckCombatState(final CombatEntry entry) {
        final DamageSource source = entry.source();
        // Paper end - Combat tracker API
        // Paper start - Fix mem leak (MC-301114)
        final io.papermc.paper.configuration.type.number.IntOr.Disabled maxTrackingCombatEntries;
        if (!this.entries.isEmpty() && (maxTrackingCombatEntries = io.papermc.paper.configuration.GlobalConfiguration.get().misc.maxTrackingCombatEntries).enabled()) {
            final int maxEntries = maxTrackingCombatEntries.intValue();
            while (this.entries.size() > maxEntries) {
                this.entries.removeFirst();
            }
        }
        // Paper end - Fix mem leak (MC-301114)
        this.entries.add(entry);
        this.lastDamageTime = this.mob.tickCount;
        this.takingDamage = true;
        if (!this.inCombat && this.mob.isAlive() && shouldEnterCombat(source)) {
            this.inCombat = true;
            this.combatStartTime = this.mob.tickCount;
            this.combatEndTime = this.combatStartTime;
            this.mob.onEnterCombat();
        }
    }

    // TODO(behaviour): vanilla recheckStatus() should delegate here when the reset condition holds
    public void resetCombatState() {
        {
            // Paper end - Combat tracker API
            boolean wasInCombat = this.inCombat;
            this.takingDamage = false;
            this.inCombat = false;
            this.combatEndTime = this.mob.tickCount;
            if (wasInCombat) {
                this.mob.onLeaveCombat();
            }

            this.entries.clear();
        }
    }
}
