package net.fissionmc.backend.mixin.lib.com.mojang.logging;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/com/mojang/logging/LogUtils.java.patch */
@Mixin(LogUtils.class)
public abstract class LogUtilsMixin {
    @Shadow @Final private static StackWalker STACK_WALKER;

    // Surface: public (see surface/members/lib.txt). Mixin requires non-public statics; the loader widens it.
    private static Logger getClassLogger() {
        return LoggerFactory.getLogger(STACK_WALKER.getCallerClass().getSimpleName());
    }
}
