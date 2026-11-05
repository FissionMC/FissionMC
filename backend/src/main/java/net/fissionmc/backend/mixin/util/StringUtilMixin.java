package net.fissionmc.backend.mixin.util;

import net.minecraft.util.StringUtil;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/util/StringUtil.java.patch */
@Mixin(StringUtil.class)
public abstract class StringUtilMixin {
    // Paper start - Username validation
    // Surface: public static. Mixin requires non-public statics; the loader widens it.
    private static boolean isReasonablePlayerName(final String name) {
        if (name.isEmpty() || name.length() > 16) {
            return false;
        }

        for (int i = 0, len = name.length(); i < len; ++i) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c == '_' || c == '.')) {
                continue;
            }

            return false;
        }

        return true;
    }
    // Paper end - Username validation
}
