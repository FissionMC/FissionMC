package net.fissionmc.backend.mixin.world.entity.monster.piglin;

import java.util.List;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.Item;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/monster/piglin/Piglin.java.patch */
@Mixin(Piglin.class)
public abstract class PiglinMixin {
    // CraftBukkit start - Custom bartering and interest list
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private java.util.Set<Item> allowedBarterItems = new java.util.HashSet<>();
    private java.util.Set<Item> interestItems = new java.util.HashSet<>();
    private static final com.mojang.serialization.Codec<java.util.Set<net.minecraft.world.item.Item>> ITEM_SET_CODEC = net.minecraft.core.registries.BuiltInRegistries.ITEM
        .byNameCodec().listOf().xmap(java.util.HashSet::new, List::copyOf);
    // CraftBukkit end
    // TODO(behaviour): "Bukkit.BarterList"/"Bukkit.InterestList" save/load via ITEM_SET_CODEC; PiglinAi consults the lists.

    // Paper - EntityPickupItemEvent fixes; onItemPickup is called in PiglinAi#pickUpItem after EntityPickupItemEvent is fired
    @WrapOperation(method = "pickUpItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/piglin/Piglin;onItemPickup(Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void paper$noEarlyPickup(final Piglin self, final ItemEntity entity, final Operation<Void> original) {
    }
}
