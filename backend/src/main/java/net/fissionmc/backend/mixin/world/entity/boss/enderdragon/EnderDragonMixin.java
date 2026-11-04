package net.fissionmc.backend.mixin.world.entity.boss.enderdragon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/boss/enderdragon/EnderDragon.java.patch */
@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {
    @Shadow public abstract BlockPos getFightOrigin();

    private @Nullable BlockPos podium;
    // TODO(behaviour): explosionSource field + checkWalls EntityExplodeEvent; egg placement uses getPodium(); knockback/addEffect/getExpReward overloads.

    // Paper start - Allow changing the EnderDragon podium
    public BlockPos getPodium() {
        if (this.podium == null) {
            return EnderDragonFight.getPodiumLocation(this.getFightOrigin());
        }
        return this.podium;
    }

    public void setPodium(@Nullable BlockPos blockPos) {
        this.podium = blockPos;
    }
    // Paper end - Allow changing the EnderDragon podium
}
