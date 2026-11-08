package net.fissionmc.backend.mixin.server.level;

import net.fissionmc.backend.relay.TravelBackend;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FissionMC: a player arriving from another backend riding a vehicle: discard ghost copies of that vehicle first (same uuids would make vanilla refuse the restored one). New file. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerVehicleMixin {
    @Inject(method = "loadAndSpawnParentVehicle", at = @At("HEAD"))
    private void fission$dropStaleVehicle(ValueInput playerInput, CallbackInfo ci) {
        TravelBackend.dropStaleVehicle(((ServerPlayer) (Object) this).level(), playerInput);
    }
}
