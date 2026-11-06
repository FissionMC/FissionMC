package net.fissionmc.backend.mixin.world.level.storage;

import com.mojang.datafixers.DataFixer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Optional;
import net.fissionmc.backend.relay.PlayerDataBackend;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FissionMC: the master owns the player data of relayed players. New file next to the ported PlayerDataStorageMixin.
 * load: the bytes the master sent replace the local file (same path as vanilla, including the data fixer); save: the file vanilla just wrote is
 * reported to the master.
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageRelayMixin {
    @Shadow @Final protected DataFixer fixerUpper;
    @Shadow @Final private File playerDir;

    @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void fission$masterData(NameAndId nameAndId, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        byte[] bytes = PlayerDataBackend.provided(nameAndId.id());
        if (bytes == null) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            int version = NbtUtils.getDataVersion(tag);
            cir.setReturnValue(Optional.of(DataFixTypes.PLAYER.updateToCurrentVersion(this.fixerUpper, tag, version)));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("FissionMC").warning("[fission] player data from the master for " + nameAndId.name() + " is unreadable, using the local file: " + e);
        }
    }

    @Inject(method = "save(Lnet/minecraft/world/entity/player/Player;)V", at = @At("RETURN"))
    private void fission$reportSave(Player player, CallbackInfo ci) {
        PlayerDataBackend.onSaved(player.getUUID(), new File(this.playerDir, player.getStringUUID() + ".dat"));
    }
}
