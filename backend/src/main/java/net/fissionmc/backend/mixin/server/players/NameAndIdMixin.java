package net.fissionmc.backend.mixin.server.players;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/players/NameAndId.java.patch */
@Mixin(NameAndId.class)
public abstract class NameAndIdMixin {
    @Shadow @Final private UUID id;
    @Shadow @Final private String name;

    // Paper start - utility method for common conversion back to the game profile
    public GameProfile toUncompletedGameProfile() {
        return new GameProfile(this.id, this.name);
    }
    // Paper end - utility method for common conversion back to the game profile
}
