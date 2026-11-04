package net.fissionmc.backend.mixin.world.entity.decoration;

import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/decoration/ArmorStand.java.patch */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private boolean canMove = true; // Paper
    // Paper start - Allow ArmorStands not to tick
    private boolean canTick = true;
    private boolean canTickSetByAPI = false;
    private boolean noTickEquipmentDirty = false;
    // Paper end - Allow ArmorStands not to tick
    // TODO(behaviour): constructor reads canTick from paperConfig; tick()/move() honour canTick/canMove; save/load "Paper.CanTick";
    //  getBukkitYaw, setItemSlot(…, silent), isCollidable(boolean), kill/brokenBy* events.
}
