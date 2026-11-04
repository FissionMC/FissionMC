package net.fissionmc.backend.mixin.world.level.saveddata.maps;

import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** FissionMC: access for MapItemSavedDataMixin#setColorsDirty(int, int, boolean). */
@Mixin(MapItemSavedData.HoldingPlayer.class)
public interface MapItemSavedData_HoldingPlayerMixin {
    @Invoker("markColorsDirty")
    void fission$markColorsDirty(int x, int y);
}
