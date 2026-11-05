package net.fissionmc.backend.mixin.util.datafix;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.DataFixerBuilder;
import com.mojang.datafixers.schemas.Schema;
import java.util.function.BiFunction;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/util/datafix/DataFixers.java.patch */
@Mixin(DataFixers.class)
public abstract class DataFixersMixin {
    // Schema v1458 = fixerUpper.addSchema(1458, V1458::new); + CraftBukkit "Player CustomName" fix right after it
    @WrapOperation(method = "addFixers", at = @At(value = "INVOKE", target = "Lcom/mojang/datafixers/DataFixerBuilder;addSchema(ILjava/util/function/BiFunction;)Lcom/mojang/datafixers/schemas/Schema;"))
    private static Schema craftbukkit$playerCustomNameFix(DataFixerBuilder fixerUpper, int version, BiFunction<Integer, Schema, Schema> factory, Operation<Schema> original) {
        final Schema schema = original.call(fixerUpper, version, factory);
        if (version == 1458) {
            fixerUpper.addFixer(new net.fissionmc.backend.datafix.PlayerCustomNameFix(schema)); // CraftBukkit
        }
        return schema;
    }
}
