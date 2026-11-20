package net.fissionmc.backend.mixin.world.entity.animal.sniffer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/animal/sniffer/Sniffer.java.patch (EntityFertilizeEggEvent; the event experience replaces the random one) */
@Mixin(Sniffer.class)
public abstract class SnifferMixin {
    @Unique private int fission$fertilizeExperience;

    @Inject(method = "spawnChildFromBreeding", at = @At("HEAD"), cancellable = true)
    private void paper$fertilizeEvent(final ServerLevel level, final Animal partner, final CallbackInfo ci) {
        // Paper start - Add EntityFertilizeEggEvent event
        final io.papermc.paper.event.entity.EntityFertilizeEggEvent result = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityFertilizeEggEvent((Animal) (Object) this, partner);
        if (result.isCancelled()) {
            ci.cancel();
            return;
        }
        this.fission$fertilizeExperience = result.getExperience();
        // Paper end - Add EntityFertilizeEggEvent event
    }

    @WrapOperation(method = "spawnChildFromBreeding", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/sniffer/Sniffer;finalizeSpawnChildFromBreeding(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V"))
    private void paper$fertilizeExperience(final Sniffer self, final ServerLevel level, final Animal partner, final AgeableMob offspring, final Operation<Void> original) {
        self.finalizeSpawnChildFromBreeding(level, partner, offspring, this.fission$fertilizeExperience); // Paper - use craftbukkit call that takes experience amount
    }
}
