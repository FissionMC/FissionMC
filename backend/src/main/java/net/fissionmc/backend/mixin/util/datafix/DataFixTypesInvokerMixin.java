package net.fissionmc.backend.mixin.util.datafix;

import com.mojang.datafixers.DSL;
import net.minecraft.util.datafix.DataFixTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Constructor invoker used by {@link DataFixTypesMixin} to create Paper's {@code DataFixTypes.NONE}. */
@Mixin(DataFixTypes.class)
public interface DataFixTypesInvokerMixin {
    @Invoker("<init>")
    static DataFixTypes fission$create(String name, int ordinal, DSL.TypeReference type) {
        throw new AssertionError();
    }
}
