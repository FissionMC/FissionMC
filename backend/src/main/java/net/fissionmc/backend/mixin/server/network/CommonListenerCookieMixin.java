package net.fissionmc.backend.mixin.server.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Paper: sources/net/minecraft/server/network/CommonListenerCookie.java.patch
 * Paper adds record components clientBrand and channels. Mixin cannot change record components, so they are fields with
 * accessors, set by {@link #create} (stand-in for Paper's canonical constructor). Defaults match Paper's createInitial.
 */
@Mixin(CommonListenerCookie.class)
public abstract class CommonListenerCookieMixin {
    private @Nullable String clientBrand = null;
    private java.util.Set<String> channels = new java.util.HashSet<>();

    public @Nullable String clientBrand() {
        return this.clientBrand;
    }

    public java.util.Set<String> channels() {
        return this.channels;
    }

    // Surface: public static. Mixin requires private statics; the loader widens it.
    private static CommonListenerCookie create(GameProfile gameProfile, int latency, ClientInformation clientInformation, boolean transferred, @Nullable String clientBrand, java.util.Set<String> channels) {
        CommonListenerCookie cookie = new CommonListenerCookie(gameProfile, latency, clientInformation, transferred);
        CommonListenerCookieMixin self = (CommonListenerCookieMixin) (Object) cookie;
        self.clientBrand = clientBrand;
        self.channels = channels;
        return cookie;
    }
}
