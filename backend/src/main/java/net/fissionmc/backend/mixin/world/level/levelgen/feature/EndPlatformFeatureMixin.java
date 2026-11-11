package net.fissionmc.backend.mixin.world.level.levelgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/level/levelgen/feature/EndPlatformFeature.java.patch (PortalCreateEvent END_PLATFORM). The 3-arg vanilla method stays (world generation: no entity, no event). */
@Mixin(EndPlatformFeature.class)
public abstract class EndPlatformFeatureMixin {
    private static void createEndPlatform(final ServerLevelAccessor newLevel, final BlockPos origin, final boolean dropResources, final Entity entity) {
        org.bukkit.craftbukkit.util.BlockStateListPopulator blockList = new org.bukkit.craftbukkit.util.BlockStateListPopulator(newLevel);
        BlockPos.MutableBlockPos pos = origin.mutable();

        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy < 3; dy++) {
                    BlockPos blockPos = pos.set(origin).move(dx, dy, dz);
                    Block block = dy == -1 ? Blocks.OBSIDIAN : Blocks.AIR;
                    if (!blockList.getBlockState(blockPos).is(block)) { // CraftBukkit
                        if (dropResources) {
                            blockList.destroyBlock(blockPos, true, null); // CraftBukkit
                        }

                        blockList.setBlock(blockPos, block.defaultBlockState(), Block.UPDATE_ALL); // CraftBukkit
                    }
                }
            }
        }

        // CraftBukkit start
        // SPIGOT-7746: Entity will only be null during world generation, which is async, so just generate without event
        if (entity != null) {
            org.bukkit.World bworld = newLevel.getLevel().getWorld();
            org.bukkit.event.world.PortalCreateEvent portalEvent = new org.bukkit.event.world.PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) blockList.getSnapshotBlocks(), bworld, entity.getBukkitEntity(), org.bukkit.event.world.PortalCreateEvent.CreateReason.END_PLATFORM);
            newLevel.getLevel().getCraftServer().getPluginManager().callEvent(portalEvent);
            if (portalEvent.isCancelled()) return;
        }

        if (dropResources) {
            blockList.placeBlocks(state -> newLevel.destroyBlock(state.getPosition(), true, null));
        } else {
            blockList.placeBlocks();
        }
        // CraftBukkit end
    }
}
