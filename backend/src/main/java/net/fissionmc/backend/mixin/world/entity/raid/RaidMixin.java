package net.fissionmc.backend.mixin.world.entity.raid;

import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/raid/Raid.java.patch */
@Mixin(Raid.class)
public abstract class RaidMixin {
    @Shadow @Final private Map<Integer, Set<Raider>> groupRaiderMap;

    @Shadow public abstract boolean isOver();
    @Shadow public abstract boolean isStopped();

    // Paper start
    private static final String PDC_NBT_KEY = "BukkitValues";
    private static final org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry PDC_TYPE_REGISTRY = new org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry();
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private final org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer persistentDataContainer = new org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer(PDC_TYPE_REGISTRY); // Paper - add persistent data container
    private int idOrNegativeOne = -1;
    // Paper end
    // TODO(behaviour): MAP_CODEC stores persistentDataContainer under PDC_NBT_KEY; raid events (RaidTrigger/Spawn/Stop/Finish).

    // CraftBukkit start
    public boolean isInProgress() {
        // FissionMC: Paper compares status == RaidStatus.ONGOING; RaidStatus is private, and ONGOING is exactly "not over, not stopped".
        return !this.isOver() && !this.isStopped();
    }
    // CraftBukkit end

    // CraftBukkit start - a method to get all raiders
    public java.util.Collection<Raider> getRaiders() {
        return this.groupRaiderMap.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());
    }
    // CraftBukkit end
}
