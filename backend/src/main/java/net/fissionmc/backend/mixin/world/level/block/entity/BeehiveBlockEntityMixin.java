package net.fissionmc.backend.mixin.world.level.block.entity;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BeehiveBlockEntity.java.patch
 * TODO(behaviour): isFull/addOccupant use maxBees, "Bukkit.MaxEntities" load/save, EntityEnterBlockEvent,
 * releaseOccupant(force) bypassing the night/rain/emergency check.
 */
@Mixin(BeehiveBlockEntity.class)
public abstract class BeehiveBlockEntityMixin {
    @Shadow @Final private List<?> stored;

    @Shadow
    private List<Entity> releaseAllOccupants(BlockState state, BeehiveBlockEntity.BeeReleaseStatus releaseStatus) {
        throw new AssertionError();
    }

    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private int maxBees = BeehiveBlockEntity.MAX_OCCUPANTS; // CraftBukkit - allow setting max amount of bees a hive can hold

    // CraftBukkit start - This allows us to bypass the night/rain/emergency check
    public List<Entity> releaseBees(final BlockState state, final BeehiveBlockEntity.BeeReleaseStatus releaseStatus, final boolean force) {
        // TODO(behaviour): Paper moves the releaseAllOccupants body here and passes `force` to releaseOccupant.
        return this.releaseAllOccupants(state, releaseStatus);
    }
    // CraftBukkit end - This allows us to bypass the night/rain/emergency check

    // Paper start - Add EntityBlockStorage clearEntities
    public void clearBees() {
        this.stored.clear();
    }
    // Paper end - Add EntityBlockStorage clearEntities
}
