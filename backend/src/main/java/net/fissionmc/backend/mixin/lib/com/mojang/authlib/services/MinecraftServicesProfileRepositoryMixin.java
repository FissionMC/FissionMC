package net.fissionmc.backend.mixin.lib.com.mojang.authlib.services;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.authlib.services.MinecraftServicesProfileRepository;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/com/mojang/authlib/services/MinecraftServicesProfileRepository.java.patch */
@Mixin(MinecraftServicesProfileRepository.class)
public abstract class MinecraftServicesProfileRepositoryMixin {

    // Paper start - Don't sleep after profile lookups if not needed
    @WrapWithCondition(method = "findProfilesByNames", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V", ordinal = 0))
    private boolean fission$skipFirstSleep(long millis, @Share("hasRequested") LocalBooleanRef hasRequested) {
        if (!hasRequested.get()) {
            hasRequested.set(true);
            return false;
        }
        return true;
    }
    // Paper end - Don't sleep after profile lookups if not needed

    // Paper start - only log details when debug is enabled
    @WrapOperation(method = "findProfileByName", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void fission$debugOnlyDetails(Logger logger, String format, Object name, Object e, Operation<Void> original) {
        if (net.minecraft.server.MinecraftServer.getServer().isDebugging() && e instanceof com.mojang.authlib.exceptions.MinecraftClientHttpException httpException) {
            logger.warn("Couldn't find profile with name: {}. Received status {} - {}", name, httpException.getStatus(), httpException.getMessage(), e);
        } else if (net.minecraft.server.MinecraftServer.getServer().isDebugging()) {
            original.call(logger, format, name, e);
        } else {
            logger.warn("Couldn't find profile with name: {}", name);
        }
    }
    // Paper end - only log details when debug is enabled
}
