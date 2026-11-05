package net.fissionmc.backend.mixin.lib.com.mojang.brigadier.tree;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Collection;
import java.util.Collections;
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
    // Paper moves the vanilla getRelevantNodes(StringReader) body here; the vanilla overload is unchanged, which is
    // equivalent to Paper's delegation with a null source (the source checks below never match null).
    @org.jetbrains.annotations.ApiStatus.Internal
    public Collection<? extends CommandNode<S>> getRelevantNodes(final StringReader input, final Object source) {
        if (literals.size() > 0) {
            final int cursor = input.getCursor();
            while (input.canRead() && input.peek() != ' ') {
                input.skip();
            }
            final String text = input.getString().substring(cursor, input.getCursor());
            input.setCursor(cursor);
            LiteralCommandNode<S> literal = null;
            if (source instanceof net.minecraft.commands.CommandSourceStack css && css.source == net.minecraft.commands.CommandSource.NULL) {
                if (!text.contains(":")) {
                    literal = this.literals.get("minecraft:" + text);
                }
            } else if (source instanceof net.minecraft.commands.CommandSourceStack css && fission$isCommandBlockSource(css.source)) { // Paper: css.source instanceof BaseCommandBlock.CloseableCommandBlockSource
                if (css.getServer().server.getCommandBlockOverride(text) && !text.contains(":")) {
                    literal = this.literals.get("minecraft:" + text);
                }
            }
            if (literal == null) {
                literal = this.literals.get(text);
            }
            if (literal != null) {
                return Collections.singleton(literal);
            } else {
                return arguments.values();
            }
        } else {
            return arguments.values();
        }
    }
    // Paper end - prioritize mc commands in function parsing

    // FissionMC: compares the class name instead of `instanceof` so the check does not class-load
    // BaseCommandBlock$CloseableCommandBlockSource; its wave-1 mixin (BaseCommandBlock_CloseableCommandBlockSourceMixin,
    // server area) currently fails to apply (CHECKCAST to another mixin class). Switch back to instanceof once fixed.
    @org.spongepowered.asm.mixin.Unique
    private static boolean fission$isCommandBlockSource(final Object source) {
        for (Class<?> c = source == null ? null : source.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("net.minecraft.world.level.BaseCommandBlock$CloseableCommandBlockSource")) return true;
        }
        return false;
    }

    // Paper start - Brigadier Command API
    public void clearAll() {
        this.children.clear();
        this.literals.clear();
        this.arguments.clear();
    }
    // Paper end - Brigadier Command API
}
