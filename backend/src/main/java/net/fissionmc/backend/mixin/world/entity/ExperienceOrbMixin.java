package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link ExperienceOrb} (paper-server/patches/sources/net/minecraft/world/entity/ExperienceOrb.java.patch).
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    @Shadow private static boolean tryMergeToExisting(ServerLevel level, Vec3 pos, int value) { throw new AssertionError(); }

    // Paper start
    // TODO(behaviour): loadPaperNBT/savePaperNBT ("Paper.ExpData") in read/addAdditionalSaveData; followNearbyPlayer target event
    private java.util.@Nullable UUID sourceEntityId;
    private java.util.@Nullable UUID triggerEntityId;
    private org.bukkit.entity.ExperienceOrb.SpawnReason spawnReason = org.bukkit.entity.ExperienceOrb.SpawnReason.UNKNOWN;
    // Paper end

    // FissionMC: static factories replacing Paper's constructors // Paper start - add reasons for orbs
    private static ExperienceOrb create(final Level level, final double x, final double y, final double z, final int value, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
        return create(level, new Vec3(x, y, z), Vec3.ZERO, value, reason, triggerId, sourceId);
    }

    private static ExperienceOrb create(final Level level, final Vec3 pos, final Vec3 roughly, final int value, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
        final ExperienceOrb orb = new ExperienceOrb(level, pos, roughly, value);
        final ExperienceOrbMixin paper = (ExperienceOrbMixin) (Object) orb;
        paper.sourceEntityId = sourceId != null ? sourceId.getUUID() : null;
        paper.triggerEntityId = triggerId != null ? triggerId.getUUID() : null;
        paper.spawnReason = reason != null ? reason : org.bukkit.entity.ExperienceOrb.SpawnReason.UNKNOWN;
        return orb;
    }
    // Paper end - add reasons for orbs

    // TODO(behaviour): vanilla awardWithDirection(level, pos, dir, amount) should delegate here with null reasons
    private static void awardWithDirection(final ServerLevel level, final Vec3 pos, final Vec3 roughDirection, int amount, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
        // Paper end - add reason to orbs
        while (amount > 0) {
            int newCount = ExperienceOrb.getExperienceValue(amount);
            amount -= newCount;
            if (!tryMergeToExisting(level, pos, newCount)) {
                level.addFreshEntity(create(level, pos, roughDirection, newCount, reason, triggerId, sourceId)); // Paper - add reason to orbs
            }
        }
    }
}
