package net.fissionmc.backend.mixin.server.dedicated;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.fissionmc.backend.boot.PaperStartup;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/server/dedicated/DedicatedPlayerList.java.patch */
@Mixin(DedicatedPlayerList.class)
public abstract class DedicatedPlayerListMixin extends net.minecraft.server.players.PlayerList {
    @Shadow private void saveIpBanList() {}
    @Shadow private void saveUserBanList() {}
    @Shadow private void loadIpBanList() {}
    @Shadow private void loadUserBanList() {}
    @Shadow private void loadOps() {}
    @Shadow private void saveOps() {}
    @Shadow private void loadWhiteList() {}
    @Shadow private void saveWhiteList() {}

    private DedicatedPlayerListMixin() {
        super(null, null, null, null);
    }

    // Paper start - fix converting txt to json file; moved from constructor
    @WrapWithCondition(method = "<init>", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;loadUserBanList()V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;saveUserBanList()V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;loadIpBanList()V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;saveIpBanList()V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;loadOps()V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;loadWhiteList()V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;saveOps()V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedPlayerList;saveWhiteList()V")
    })
    private boolean paper$deferFileLoading(final DedicatedPlayerList self) {
        return !PaperStartup.active(); // FissionMC: Paper's DedicatedServer.initServer calls loadAndSaveFiles() instead
    }

    // Surface: public (Paper declares it abstract on PlayerList and implements it here).
    public void loadAndSaveFiles() {
        // Paper end - fix converting txt to json file
        this.loadUserBanList();
        this.saveUserBanList();
        this.loadIpBanList();
        this.saveIpBanList();
        this.loadOps();
        this.loadWhiteList();
        this.saveOps();
        if (!this.getWhiteList().getFile().exists()) {
            this.saveWhiteList();
        }
    }
}
