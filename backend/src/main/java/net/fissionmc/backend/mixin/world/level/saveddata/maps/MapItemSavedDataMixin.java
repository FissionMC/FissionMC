package net.fissionmc.backend.mixin.world.level.saveddata.maps;

import java.util.List;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/saveddata/maps/MapItemSavedData.java.patch */
@Mixin(MapItemSavedData.class)
public abstract class MapItemSavedDataMixin extends SavedData {
    @Shadow @Final private List<MapItemSavedData.HoldingPlayer> carriedBy;

    // CraftBukkit start
    private org.bukkit.craftbukkit.map.CraftMapView mapView;
    private java.util.UUID uniqueId;
    private MapId id;
    // CraftBukkit end
    // TODO(behaviour): vanillaRender, UUID-backed dimension codec, cursor limits, HoldingPlayer render hunks

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$init(CallbackInfo ci) {
        // CraftBukkit start
        if (this.mapView == null) {
            this.mapView = new org.bukkit.craftbukkit.map.CraftMapView((MapItemSavedData) (Object) this);
        }
        // CraftBukkit end
    }

    // Paper start - Fix unnecessary map data saves
    // TODO(behaviour): vanilla setColorsDirty(int, int) -> setColorsDirty(x, y, true)
    public void setColorsDirty(final int x, final int y, final boolean markFileDirty) {
        if (markFileDirty) this.setDirty();
        // Paper end - Fix unnecessary map data saves

        for (MapItemSavedData.HoldingPlayer holdingPlayer : this.carriedBy) {
            ((MapItemSavedData_HoldingPlayerMixin) holdingPlayer).fission$markColorsDirty(x, y);
        }
    }
}
