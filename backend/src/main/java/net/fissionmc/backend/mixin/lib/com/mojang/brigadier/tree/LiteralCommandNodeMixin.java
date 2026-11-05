package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.tree;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/com/mojang/brigadier/tree/LiteralCommandNode.java.patch */
@Mixin(LiteralCommandNode.class)
public abstract class LiteralCommandNodeMixin<S> {
    @Unique private String fission$nonPrefixed; // Paper - prioritize mc commands in function parsing

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fission$initNonPrefixed(String literal, Command<S> command, Predicate<S> requirement, CommandNode<S> redirect, RedirectModifier<S> modifier, boolean forks, CallbackInfo ci) {
        // Paper start - prioritize mc commands in function parsing
        if (literal.startsWith("minecraft:")) {
            this.fission$nonPrefixed = literal.substring("minecraft:".length());
        } else {
            this.fission$nonPrefixed = null;
        }
        // Paper end - prioritize mc commands in function parsing
    }

    // Paper start - prioritize mc commands in function parsing: second pass with the non-prefixed literal
    @WrapOperation(method = "parse(Lcom/mojang/brigadier/StringReader;Lcom/mojang/brigadier/context/CommandContextBuilder;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/LiteralCommandNode;parse(Lcom/mojang/brigadier/StringReader;)I"))
    private int fission$parseSecondPass(LiteralCommandNode<S> self, StringReader reader, Operation<Integer> original) {
        int end = original.call(self, reader);
        if (end == -1 && this.fission$nonPrefixed != null) {
            end = fission$parse(reader, this.fission$nonPrefixed);
        }
        return end;
    }

    @Unique
    private static int fission$parse(final StringReader reader, final String literal) {
        // vanilla parse(StringReader) with the literal as a parameter
        final int start = reader.getCursor();
        if (reader.canRead(literal.length())) {
            final int end = start + literal.length();
            if (reader.getString().substring(start, end).equals(literal)) {
                reader.setCursor(end);
                if (!reader.canRead() || reader.peek() == ' ') {
                    return end;
                } else {
                    reader.setCursor(start);
                }
            }
        }
        return -1;
    }
    // Paper end - prioritize mc commands in function parsing
}
