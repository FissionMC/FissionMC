package net.fissionmc.craft.compat;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

/**
 * Paper turns {@code LecternBlockEntity#bookAccess} into the named inner class {@code LecternBlockEntity.LecternInventory}.
 * FissionMC cannot add nested classes to vanilla, so the vanilla anonymous class implements this interface instead
 * (backend LecternBlockEntity_1Mixin) and ported call sites use it in place of {@code LecternBlockEntity.LecternInventory}.
 */
public interface LecternInventory extends Container {
    LecternBlockEntity getLectern();
}
