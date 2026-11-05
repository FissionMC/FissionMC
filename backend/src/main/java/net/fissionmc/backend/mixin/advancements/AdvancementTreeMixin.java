package net.fissionmc.backend.mixin.advancements;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementTree;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/advancements/AdvancementTree.java.patch */
@Mixin(AdvancementTree.class)
public abstract class AdvancementTreeMixin {

    @WrapOperation(method = "remove(Lnet/minecraft/advancements/AdvancementNode;)V", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"))
    private void fission$forgotAtDebug(Logger logger, String format, Object holder, Operation<Void> original) {
        logger.debug(format, holder); // Paper - Improve logging and errors
    }

    // LOGGER.info("Loaded {} advancements", this.nodes.size()); // CraftBukkit - moved to ServerAdvancementManager#apply // Paper - Improve logging and errors
    @WrapWithCondition(method = "addAll", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"))
    private boolean fission$noLoadedLog(Logger logger, String format, Object count) {
        return false;
    }
}
