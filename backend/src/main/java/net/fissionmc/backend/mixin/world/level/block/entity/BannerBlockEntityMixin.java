package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/BannerBlockEntity.java.patch
 * TODO(behaviour): loadAdditional / applyImplicitComponents go through setPatterns (apply limits).
 */
@Mixin(BannerBlockEntity.class)
public abstract class BannerBlockEntityMixin {
    @Shadow private BannerPatternLayers patterns;

    // CraftBukkit start
    public void setPatterns(BannerPatternLayers bannerPatternLayers) {
        if (bannerPatternLayers.layers().size() > 20) {
            bannerPatternLayers = new BannerPatternLayers(java.util.List.copyOf(bannerPatternLayers.layers().subList(0, 20)));
        }
        this.patterns = bannerPatternLayers;
    }
    // CraftBukkit end
}
