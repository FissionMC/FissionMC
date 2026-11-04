package net.fissionmc.backend.mixin.world.level.dimension.end;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/level/dimension/end/EnderDragonFight.java.patch
 * Paper changes respawnDragon/spawnNewGateway/tryRespawn to return boolean; those return-type changes are handled at the
 * CraftDragonBattle call sites (FissionMC edits) and spawnNewGateway() is widened in surface/integration.at.
 * TODO(behaviour): tick() respawn-crystal check honours emptyCrystalRespawnAllowed; legacy-dragon scan / shouldRemoveDragon
 * config; DragonEggFormEvent; softlocked exit portal fix; resetSpikeCrystals on spawn; boss event name reset; tryRespawn(BlockPos).
 */
@Mixin(EnderDragonFight.class)
public abstract class EnderDragonFightMixin {
    @Shadow private ServerLevel level;

    private boolean emptyCrystalRespawnAllowed = false; // Paper - Support allowing the dragon to respawn if no crystals are found

    @Inject(method = "setRespawnStage", at = @At("HEAD"))
    private void fission$resetEmptyCrystalRespawn(final DragonRespawnStage stage, final CallbackInfo ci) {
        if (stage == DragonRespawnStage.END) {
            this.emptyCrystalRespawnAllowed = false; // Paper - Support allowing the dragon to respawn if no crystals are found
        }
    }

    @Inject(method = "abortRespawnSequence", at = @At("HEAD"))
    private void fission$abortEmptyCrystalRespawn(final CallbackInfo ci) {
        this.emptyCrystalRespawnAllowed = false; // Paper - Support allowing the dragon to respawn if no crystals are found
    }

    // Paper start - More DragonBattle API
    public List<EndCrystal> getSpikeCrystals() {
        final List<EndCrystal> crystals = new ArrayList<>();
        for (final EndSpikeFeature.EndSpike spike : EndSpikeFeature.getSpikesForLevel(this.level)) {
            crystals.addAll(this.level.getEntitiesOfClass(EndCrystal.class, spike.getTopBoundingBox()));
        }
        return crystals;
    }
    // Paper end - More DragonBattle API
}
