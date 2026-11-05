package net.fissionmc.backend.mixin.advancements.predicates;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/advancements/predicates/LocationPredicate.java.patch */
@Mixin(LocationPredicate.class)
public abstract class LocationPredicateMixin {

    // Paper - Add option for strict advancement dimension checks
    @WrapOperation(method = "matches", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> fission$mainDimensionKey(ServerLevel level, Operation<ResourceKey<Level>> original) {
        return io.papermc.paper.configuration.GlobalConfiguration.get().misc.strictAdvancementDimensionCheck ? original.call(level) : org.bukkit.craftbukkit.util.CraftDimensionUtil.getMainDimensionKey(level); // Paper - Add option for strict advancement dimension checks
    }
}
