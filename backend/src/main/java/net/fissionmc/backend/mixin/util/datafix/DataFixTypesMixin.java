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

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private com.mojang.datafixers.DSL.TypeReference type;

    @org.spongepowered.asm.mixin.injection.Inject(method = "update(Lcom/mojang/datafixers/DataFixer;Lcom/mojang/serialization/Dynamic;II)Lcom/mojang/serialization/Dynamic;", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private <T> void paper$noOpFix(com.mojang.datafixers.DataFixer fixerUpper, com.mojang.serialization.Dynamic<T> input, int fromVersion, int toVersion, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<com.mojang.serialization.Dynamic<T>> cir) {
        if (this.type == null) cir.setReturnValue(input); // Paper - add no-op fix for custom types until we actually need fixers for them
    }
}
