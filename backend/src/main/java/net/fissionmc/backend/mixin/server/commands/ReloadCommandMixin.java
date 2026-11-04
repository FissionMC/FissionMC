package net.fissionmc.backend.mixin.server.commands;

import java.util.Collection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.ReloadCommand;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/commands/ReloadCommand.java.patch */
@Mixin(ReloadCommand.class)
public abstract class ReloadCommandMixin {
    @Shadow private static Collection<String> discoverNewPacks(final PackRepository packRepository, final WorldData worldData, final Collection<String> currentPacks) { throw new AssertionError(); }

    // CraftBukkit start
    private static void reload(MinecraftServer server) {
        PackRepository packRepository = server.getPackRepository();
        WorldData worldData = server.getWorldData();
        Collection<String> selectedIds = packRepository.getSelectedIds();
        Collection<String> collection = discoverNewPacks(packRepository, worldData, selectedIds);
        server.reloadResources(collection, io.papermc.paper.event.server.ServerResourcesReloadedEvent.Cause.PLUGIN); // Paper - Add ServerResourcesReloadedEvent
    }
    // CraftBukkit end
}
