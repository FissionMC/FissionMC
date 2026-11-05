package net.fissionmc.backend.mixin.util.datafix.fixes;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Dynamic;
import net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/util/datafix/fixes/ItemStackTheFlatteningFix.java.patch */
@Mixin(ItemStackTheFlatteningFix.class)
public abstract class ItemStackTheFlatteningFixMixin {
    // tagRest = tagRest.set("Damage", tagRest.createInt(data)) -> only if data != 0
    @WrapOperation(method = "lambda$makeRule$0", at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Dynamic;set(Ljava/lang/String;Lcom/mojang/serialization/Dynamic;)Lcom/mojang/serialization/Dynamic;"))
    private static Dynamic<?> craftbukkit$skipZeroDamage(Dynamic<?> tagRest, String key, Dynamic<?> value, Operation<Dynamic<?>> original) {
        if (value.asInt(0) != 0) return original.call(tagRest, key, value); // CraftBukkit
        return tagRest;
    }
}
