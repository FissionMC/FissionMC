package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.tree;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Collection;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/com/mojang/brigadier/tree/CommandNode.java.patch */
@Mixin(CommandNode.class)
public abstract class CommandNodeMixin<S> {
    @Shadow @Final private Map<String, CommandNode<S>> children;
    @Shadow @Final private Map<String, LiteralCommandNode<S>> literals;
    @Shadow @Final private Map<String, ArgumentCommandNode<S, ?>> arguments;

    @Shadow public abstract Collection<? extends CommandNode<S>> getRelevantNodes(StringReader input);

    // `requirement` is widened to public non-final by surface/misc.at (Paper: "public-f").
    // Fields below: surface declares them public; the loader widens after Mixin.
    private CommandNode<S> clientNode; // Paper - Brigadier API
    private CommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> unwrappedCached = null; // Paper - Brigadier Command API
    private CommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> wrappedCached = null; // Paper - Brigadier Command API
    private io.papermc.paper.command.brigadier.APICommandMeta apiCommandMeta; // Paper - Brigadier Command API

    // CraftBukkit start
    public void removeCommand(String name) {
        this.children.remove(name);
        this.literals.remove(name);
        this.arguments.remove(name);
    }
    // CraftBukkit end

    // Paper start - prioritize mc commands in function parsing
    @org.jetbrains.annotations.ApiStatus.Internal
    public Collection<? extends CommandNode<S>> getRelevantNodes(final StringReader input, final Object source) {
        // TODO(behaviour): Paper moves the vanilla getRelevantNodes(StringReader) body here and adds the
        // "minecraft:" literal preference for CommandSource.NULL / command-block sources; the vanilla
        // overload then delegates here, and CommandDispatcher#parseNodes calls this overload with the source.
        return this.getRelevantNodes(input);
    }
    // Paper end - prioritize mc commands in function parsing

    // Paper start - Brigadier Command API
    public void clearAll() {
        this.children.clear();
        this.literals.clear();
        this.arguments.clear();
    }
    // Paper end - Brigadier Command API
}
