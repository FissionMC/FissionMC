package net.fissionmc.backend.mixin.util.datafix;

import net.minecraft.util.datafix.DataFixTypes;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/util/datafix/DataFixTypes.java.patch */
@Mixin(DataFixTypes.class)
public abstract class DataFixTypesMixin {
    // Paper adds the enum constant `NONE(null)` (ordinal 0). Mixin cannot add enum constants, so NONE is created
    // through the constructor invoker after the vanilla constants (it is not part of values()).
    // Surface: public static final. Mixin requires non-public fields; the loader widens it.
    private static final DataFixTypes NONE = DataFixTypesInvokerMixin.fission$create("NONE", DataFixTypes.values().length, null); // Paper - add no-op fix for custom types until we actually need fixers for them

    // TODO(behaviour): update(DataFixer, Dynamic, int, int): `if (this.type == null) return input;` (Paper - add no-op fix
    // for custom types). Until ported, NONE must not reach DataFixer#update.
}
