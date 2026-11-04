package net.fissionmc.backend.mixin.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/commands/arguments/EntityArgument.java.patch */
@Mixin(EntityArgument.class)
public abstract class EntityArgumentMixin {
    @Shadow @Final public static SimpleCommandExceptionType ERROR_NOT_SINGLE_PLAYER;
    @Shadow @Final public static SimpleCommandExceptionType ERROR_NOT_SINGLE_ENTITY;
    @Shadow @Final public static SimpleCommandExceptionType ERROR_ONLY_PLAYERS_ALLOWED;
    @Shadow @Final private boolean single;
    @Shadow @Final private boolean playersOnly;

    // CraftBukkit start
    // TODO(behaviour): vanilla parse(StringReader, boolean) -> this(reader, allowSelectors, false); listSuggestions permission hunk
    public EntitySelector parse(final StringReader reader, final boolean allowSelectors, final boolean overridePermissions) throws CommandSyntaxException {
        // CraftBukkit end
        int start = 0;
        EntitySelectorParser parser = new EntitySelectorParser(reader, allowSelectors);
        EntitySelector selector = parser.parse(overridePermissions); // CraftBukkit
        if (selector.getMaxResults() > 1 && this.single) {
            if (this.playersOnly) {
                reader.setCursor(0);
                throw ERROR_NOT_SINGLE_PLAYER.createWithContext(reader);
            } else {
                reader.setCursor(0);
                throw ERROR_NOT_SINGLE_ENTITY.createWithContext(reader);
            }
        } else if (selector.includesEntities() && this.playersOnly && !selector.isSelfSelector()) {
            reader.setCursor(0);
            throw ERROR_ONLY_PLAYERS_ALLOWED.createWithContext(reader);
        } else {
            return selector;
        }
    }
}
