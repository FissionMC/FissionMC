package net.fissionmc.backend.mixin.world.scores;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/scores/Objective.java.patch (Lazy initialization because only used internally by commands).
 * The vanilla field formattedDisplayName plays the role of Paper's lazyFormattedDisplayName.
 */
@Mixin(Objective.class)
public abstract class ObjectiveMixin {
    @Shadow private Component formattedDisplayName;

    @Shadow private Component createFormattedDisplayName() { throw new AssertionError(); }

    // constructor: this.formattedDisplayName = this.createFormattedDisplayName(); removed
    // setDisplayName: this.lazyFormattedDisplayName = null;
    @WrapOperation(method = {"<init>", "setDisplayName"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Objective;createFormattedDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component paper$lazyFormattedDisplayName(Objective self, Operation<Component> original) {
        return null; // Paper - Lazy initialization because only used internally by commands
    }

    @ModifyReturnValue(method = "getFormattedDisplayName", at = @At("RETURN"))
    private Component paper$createFormattedDisplayNameLazily(Component formatted) {
        return formatted != null ? formatted : (this.formattedDisplayName = this.createFormattedDisplayName()); // Paper - Lazy initialization because only used internally by commands
    }
}
