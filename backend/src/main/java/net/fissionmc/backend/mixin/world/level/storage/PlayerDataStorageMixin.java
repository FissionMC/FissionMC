package net.fissionmc.backend.mixin.world.level.storage;

import java.io.File;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/storage/PlayerDataStorage.java.patch */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @Shadow @Final private File playerDir;

    // CraftBukkit start
    public File getPlayerDir() {
        return this.playerDir;
    }
    // CraftBukkit end
}
