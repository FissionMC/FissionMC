package net.fissionmc.backend.mixin.lib.com.mojang.brigadier;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/com/mojang/brigadier/CommandDispatcher.java.patch */
@Mixin(CommandDispatcher.class)
public abstract class CommandDispatcherMixin<S> {

    // Paper start - prioritize mc commands in function parsing
    @WrapOperation(method = "parseNodes", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;getRelevantNodes(Lcom/mojang/brigadier/StringReader;)Ljava/util/Collection;"))
    private Collection<? extends CommandNode<S>> fission$relevantNodesWithSource(CommandNode<S> node, StringReader originalReader, Operation<Collection<? extends CommandNode<S>>> original,
                                                                               CommandNode<S> parent, StringReader reader, CommandContextBuilder<S> contextSoFar) {
        return node.getRelevantNodes(originalReader, contextSoFar.getSource());
    }
    // Paper end - prioritize mc commands in function parsing

    // Paper start - Handle non-recoverable exceptions
    @WrapOperation(method = "parseNodes", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;parse(Lcom/mojang/brigadier/StringReader;Lcom/mojang/brigadier/context/CommandContextBuilder;)V"))
    private void fission$markNonRecoverable(CommandNode<S> child, StringReader reader, CommandContextBuilder<S> context, Operation<Void> original,
                                            @Share("stop") LocalBooleanRef stop) {
        try {
            original.call(child, reader, context);
        } catch (final Throwable e) {
            if (e instanceof io.papermc.paper.brigadier.TagParseCommandSyntaxException) {
                stop.set(true);
            }
            throw e;
        }
    }

    @Inject(method = "parseNodes", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;setCursor(I)V", shift = At.Shift.AFTER), cancellable = true)
    private void fission$stopOnNonRecoverable(CommandNode<S> node, StringReader originalReader, CommandContextBuilder<S> contextSoFar,
                                              CallbackInfoReturnable<ParseResults<S>> cir,
                                              @Local Map<CommandNode<S>, CommandSyntaxException> errors, @Share("stop") LocalBooleanRef stop) {
        if (stop.get()) cir.setReturnValue(new ParseResults<>(contextSoFar, originalReader, errors));
    }
    // Paper end - Handle non-recoverable exceptions

    // Paper start - getSmartUsage with a null source lists every child
    @WrapOperation(method = "getSmartUsage(Lcom/mojang/brigadier/tree/CommandNode;Ljava/lang/Object;ZZ)Ljava/lang/String;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;canUse(Ljava/lang/Object;)Z"))
    private boolean fission$smartUsageNullSource(CommandNode<S> node, Object source, Operation<Boolean> original) {
        return source == null || original.call(node, source); // Paper
    }

    // children filter lambda: `c -> source == null || c.canUse(source)`
    @WrapOperation(method = "lambda$getSmartUsage$2", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;canUse(Ljava/lang/Object;)Z"))
    private static boolean fission$smartUsageChildrenNullSource(CommandNode<?> node, Object source, Operation<Boolean> original) {
        return source == null || original.call(node, source); // Paper
    }
    // Paper end

    // Paper start - Don't suggest if the requirement isn't met
    @WrapOperation(method = "getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;listSuggestions(Lcom/mojang/brigadier/context/CommandContext;Lcom/mojang/brigadier/suggestion/SuggestionsBuilder;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Suggestions> fission$suggestOnlyIfUsable(CommandNode<S> node, CommandContext<S> context, SuggestionsBuilder builder, Operation<CompletableFuture<Suggestions>> original) {
        if (node.canUse(context.getSource())) return original.call(node, context, builder);
        return Suggestions.empty();
    }
    // Paper end - Don't suggest if the requirement isn't met

    // Paper start - propagate failures
    @WrapOperation(method = "getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenRun(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> fission$propagateFailures(CompletableFuture<Void> all, Runnable action, Operation<CompletableFuture<Void>> original,
                                                             @Local CompletableFuture<Suggestions> result) {
        return all.whenComplete(($, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(throwable);
                return;
            }
            action.run();
        });
    }
    // Paper end - propagate failures
}
