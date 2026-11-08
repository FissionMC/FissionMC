package net.fissionmc.backend.commands;

import com.mojang.brigadier.tree.CommandNode;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionProviderCheck;

/**
 * Paper - Vanilla command permission fixes. Paper's patch adds a record component {@code vanillaNode} (an AtomicReference) to
 * {@code PermissionProviderCheck}; a record cannot get a component through Mixin, so the node of each vanilla command requirement is kept here
 * (identity keys: records compare by value). Filled by {@code CommandsMixin} when the dispatcher is built, read by {@code PermissionProviderCheckMixin}.
 */
public final class VanillaCommandPermissions {
    private VanillaCommandPermissions() {}

    private static final Map<PermissionProviderCheck<?>, CommandNode<CommandSourceStack>> NODES = Collections.synchronizedMap(new IdentityHashMap<>());

    public static void register(final PermissionProviderCheck<?> check, final CommandNode<CommandSourceStack> node) {
        NODES.put(check, node);
    }

    public static CommandNode<CommandSourceStack> nodeOf(final PermissionProviderCheck<?> check) {
        return NODES.get(check);
    }
}
