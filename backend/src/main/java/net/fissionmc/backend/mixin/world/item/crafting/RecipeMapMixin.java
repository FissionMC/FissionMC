package net.fissionmc.backend.mixin.world.item.crafting;

import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/item/crafting/RecipeMap.java.patch
 * TODO(behaviour): RecipeMap.create must build mutable maps (LinkedHashMultimap / LinkedHashMap) for addRecipe/removeRecipe,
 * and addRecipe marks holders as runtime-added (RecipeHolder#asRuntimeAddedRecipe, not portable to the record).
 */
@Mixin(RecipeMap.class)
public abstract class RecipeMapMixin {
    @Shadow @Final private Multimap<net.minecraft.world.item.crafting.RecipeType<?>, RecipeHolder<?>> byType;
    @Shadow @Final private Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey;

    @Shadow public abstract <I extends net.minecraft.world.item.crafting.RecipeInput, T extends Recipe<I>> Collection<RecipeHolder<T>> byType(net.minecraft.world.item.crafting.RecipeType<T> type);

    // CraftBukkit start - mutable
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static RecipeMap createEmpty() {
        // FissionMC: the RecipeMap constructor is private (javac cannot call it from the mixin), so construct reflectively.
        try {
            final java.lang.reflect.Constructor<RecipeMap> ctor = RecipeMap.class.getDeclaredConstructor(Multimap.class, Map.class);
            ctor.setAccessible(true);
            return ctor.newInstance(com.google.common.collect.LinkedHashMultimap.create(), com.google.common.collect.Maps.newLinkedHashMap());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public void addRecipe(RecipeHolder<?> holder) {
        // TODO(behaviour): holder = holder.asRuntimeAddedRecipe(); // Mark recipes holder as added during runtime
        Collection<RecipeHolder<?>> recipes = this.byType.get(holder.value().getType());

        if (this.byKey.containsKey(holder.id())) {
            throw new IllegalStateException("Duplicate recipe ignored with ID " + holder.id());
        } else {
            recipes.add(holder);
            this.byKey.put(holder.id(), holder);
        }
    }
    // CraftBukkit end

    // Paper start - replace removeRecipe implementation
    public boolean removeRecipe(ResourceKey<Recipe<?>> id) {
        final RecipeHolder<?> remove = this.byKey.remove(id);
        if (remove == null) {
            return false;
        }
        final Collection<? extends RecipeHolder<?>> recipes = this.byType(remove.value().getType());
        return recipes.remove(remove);
    }
    // Paper end - replace removeRecipe implementation
}
