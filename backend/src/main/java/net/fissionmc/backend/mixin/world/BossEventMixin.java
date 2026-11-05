package net.fissionmc.backend.mixin.world;

import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/BossEvent.java.patch (getters/setters delegate to the adventure BossBar when one is attached) */
@Mixin(BossEvent.class)
public abstract class BossEventMixin {
    private net.kyori.adventure.bossbar.BossBar adventure; // Paper

    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void paper$getName(final CallbackInfoReturnable<Component> cir) {
        if (this.adventure != null) cir.setReturnValue(io.papermc.paper.adventure.PaperAdventure.asVanilla(this.adventure.name())); // Paper
    }

    @Inject(method = "setName", at = @At("HEAD"))
    private void paper$setName(final Component name, final CallbackInfo ci) {
        if (this.adventure != null) this.adventure.name(io.papermc.paper.adventure.PaperAdventure.asAdventure(name)); // Paper
    }

    @Inject(method = "getProgress", at = @At("HEAD"), cancellable = true)
    private void paper$getProgress(final CallbackInfoReturnable<Float> cir) {
        if (this.adventure != null) cir.setReturnValue(this.adventure.progress()); // Paper
    }

    @Inject(method = "setProgress", at = @At("HEAD"))
    private void paper$setProgress(final float progress, final CallbackInfo ci) {
        if (this.adventure != null) this.adventure.progress(progress); // Paper
    }

    @Inject(method = "getColor", at = @At("HEAD"), cancellable = true)
    private void paper$getColor(final CallbackInfoReturnable<BossEvent.BossBarColor> cir) {
        if (this.adventure != null) cir.setReturnValue(io.papermc.paper.adventure.PaperAdventure.asVanilla(this.adventure.color())); // Paper
    }

    @Inject(method = "setColor", at = @At("HEAD"))
    private void paper$setColor(final BossEvent.BossBarColor color, final CallbackInfo ci) {
        if (this.adventure != null) this.adventure.color(io.papermc.paper.adventure.PaperAdventure.asAdventure(color)); // Paper
    }

    @Inject(method = "getOverlay", at = @At("HEAD"), cancellable = true)
    private void paper$getOverlay(final CallbackInfoReturnable<BossEvent.BossBarOverlay> cir) {
        if (this.adventure != null) cir.setReturnValue(io.papermc.paper.adventure.PaperAdventure.asVanilla(this.adventure.overlay())); // Paper
    }

    @Inject(method = "setOverlay", at = @At("HEAD"))
    private void paper$setOverlay(final BossEvent.BossBarOverlay overlay, final CallbackInfo ci) {
        if (this.adventure != null) this.adventure.overlay(io.papermc.paper.adventure.PaperAdventure.asAdventure(overlay)); // Paper
    }

    @Inject(method = "shouldDarkenScreen", at = @At("HEAD"), cancellable = true)
    private void paper$shouldDarkenScreen(final CallbackInfoReturnable<Boolean> cir) {
        if (this.adventure != null) cir.setReturnValue(this.adventure.hasFlag(net.kyori.adventure.bossbar.BossBar.Flag.DARKEN_SCREEN)); // Paper
    }

    @Inject(method = "setDarkenScreen", at = @At("HEAD"))
    private void paper$setDarkenScreen(final boolean darkenScreen, final CallbackInfoReturnable<BossEvent> cir) {
        if (this.adventure != null) io.papermc.paper.adventure.PaperAdventure.setFlag(this.adventure, net.kyori.adventure.bossbar.BossBar.Flag.DARKEN_SCREEN, darkenScreen); // Paper
    }

    @Inject(method = "shouldPlayBossMusic", at = @At("HEAD"), cancellable = true)
    private void paper$shouldPlayBossMusic(final CallbackInfoReturnable<Boolean> cir) {
        if (this.adventure != null) cir.setReturnValue(this.adventure.hasFlag(net.kyori.adventure.bossbar.BossBar.Flag.PLAY_BOSS_MUSIC)); // Paper
    }

    @Inject(method = "setPlayBossMusic", at = @At("HEAD"))
    private void paper$setPlayBossMusic(final boolean playBossMusic, final CallbackInfoReturnable<BossEvent> cir) {
        if (this.adventure != null) io.papermc.paper.adventure.PaperAdventure.setFlag(this.adventure, net.kyori.adventure.bossbar.BossBar.Flag.PLAY_BOSS_MUSIC, playBossMusic); // Paper
    }

    @Inject(method = "setCreateWorldFog", at = @At("HEAD"))
    private void paper$setCreateWorldFog(final boolean createWorldFog, final CallbackInfoReturnable<BossEvent> cir) {
        if (this.adventure != null) io.papermc.paper.adventure.PaperAdventure.setFlag(this.adventure, net.kyori.adventure.bossbar.BossBar.Flag.CREATE_WORLD_FOG, createWorldFog); // Paper
    }

    @Inject(method = "shouldCreateWorldFog", at = @At("HEAD"), cancellable = true)
    private void paper$shouldCreateWorldFog(final CallbackInfoReturnable<Boolean> cir) {
        if (this.adventure != null) cir.setReturnValue(this.adventure.hasFlag(net.kyori.adventure.bossbar.BossBar.Flag.CREATE_WORLD_FOG)); // Paper
    }
}
