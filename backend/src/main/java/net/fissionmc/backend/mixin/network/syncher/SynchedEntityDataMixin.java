package net.fissionmc.backend.mixin.network.syncher;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/network/syncher/SynchedEntityData.java.patch */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {
    @Shadow @Final private SynchedEntityData.DataItem<?>[] itemsById;

    // Paper start
    public List<SynchedEntityData.DataValue<?>> packAll() {
        final List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(this.itemsById.length);
        for (final SynchedEntityData.DataItem<?> item : this.itemsById) {
            values.add(item.value());
        }

        return values;
    }
    // Paper end
}
