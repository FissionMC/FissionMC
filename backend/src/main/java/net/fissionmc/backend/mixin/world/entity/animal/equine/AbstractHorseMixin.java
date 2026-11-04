package net.fissionmc.backend.mixin.world.entity.animal.equine;

import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/animal/equine/AbstractHorse.java.patch */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin {
    @Shadow @Final private static int FLAG_OPEN_MOUTH;

    @Shadow protected abstract boolean getFlag(int flag);
    @Shadow protected abstract void setFlag(int flag, boolean value);

    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private int maxDomestication = 100; // CraftBukkit - store max domestication value
    // TODO(behaviour): getMaxTemper returns maxDomestication; "Bukkit.MaxDomestication" save/load; Llama constructor sets 30.

    // Paper start - Horse API
    public void setMouthOpen(boolean open) {
        this.setFlag(AbstractHorseMixin.FLAG_OPEN_MOUTH, open);
    }

    public boolean isMouthOpen() {
        return this.getFlag(AbstractHorseMixin.FLAG_OPEN_MOUTH);
    }
    // Paper end - Horse API
}
