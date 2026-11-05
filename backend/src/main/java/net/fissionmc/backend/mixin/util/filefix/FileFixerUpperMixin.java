package net.fissionmc.backend.mixin.util.filefix;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.filefix.FileFixerUpper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/util/filefix/FileFixerUpper.java.patch */
@Mixin(FileFixerUpper.class)
public abstract class FileFixerUpperMixin {
    @WrapOperation(method = "fix(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/serialization/Dynamic;Lnet/minecraft/util/worldupdate/UpgradeProgress;I)Lcom/mojang/serialization/Dynamic;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/filefix/FileFixerUpper;requiresFileFixing(I)Z"))
    private boolean paper$warnInitialMigration(FileFixerUpper self, int loadedVersion, Operation<Boolean> original) {
        final boolean requires = original.call(self, loadedVersion);
        if (requires) {
            if (!io.papermc.paper.world.migration.WorldFolderMigration.didInitialLoad && FileFixerUpper.worldVersionToFileFixerVersion(loadedVersion) == 0) io.papermc.paper.world.migration.WorldFolderMigration.warnAndDelayStartupMigration(); // Paper - warn and delay migration if performing the intial migration from 1.21.11 to >= 26.1
        }
        return requires;
    }
    // Paper - print correct stacktrace (MC-308790): not ported (log/exception argument only)
}
