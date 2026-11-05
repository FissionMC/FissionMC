package net.fissionmc.backend.mixin.util.datafix.fixes;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Dynamic;
import net.minecraft.util.datafix.fixes.ItemStackMapIdFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/util/datafix/fixes/ItemStackMapIdFix.java.patch */
@Mixin(ItemStackMapIdFix.class)
public abstract class ItemStackMapIdFixMixin {
    @WrapOperation(method = "lambda$makeRule$0", at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Dynamic;set(Ljava/lang/String;Lcom/mojang/serialization/Dynamic;)Lcom/mojang/serialization/Dynamic;"))
    private static Dynamic<?> craftbukkit$keepExistingMap(Dynamic<?> tagRest, String key, Dynamic<?> value, Operation<Dynamic<?>> original) {
        if (tagRest.getElement("map").result().isEmpty()) return original.call(tagRest, key, value); // CraftBukkit
        return tagRest;
    }
}
