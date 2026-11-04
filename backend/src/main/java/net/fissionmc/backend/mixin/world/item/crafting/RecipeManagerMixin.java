package net.fissionmc.backend.mixin.world.item.crafting;

import java.util.Collection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/item/crafting/RecipeManager.java.patch
 * TODO(behaviour): getRecipeFor prioritises runtime-added recipes.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow @Final @Mutable private RecipeMap recipes;
    @Shadow @Final @Mutable private Collection<RecipeHolder<?>> learnableRecipes;

    @Shadow public abstract void finalizeRecipeLoading(FeatureFlagSet enabledFlags);

    // CraftBukkit start
    public void addRecipe(RecipeHolder<?> holder) {
        org.spigotmc.AsyncCatcher.catchOp("Recipe Add"); // Spigot
        this.recipes.addRecipe(holder);
        this.finalizeRecipeLoading();
    }

    private FeatureFlagSet enabledFlags;

    public void finalizeRecipeLoading() {
        this.learnableRecipes = this.recipes.values().stream().filter(r -> !r.value().isSpecial()).toList(); // Paper - refresh learnable recipes after runtime recipe changes
        if (this.enabledFlags != null) {
            this.finalizeRecipeLoading(this.enabledFlags);

            net.minecraft.server.MinecraftServer.getServer().getPlayerList().reloadResources();
        }
    }

    @Inject(method = "finalizeRecipeLoading(Lnet/minecraft/world/flag/FeatureFlagSet;)V", at = @At("HEAD"))
    private void fission$storeEnabledFlags(final FeatureFlagSet enabledFlags, final CallbackInfo ci) {
        this.enabledFlags = enabledFlags;
    }
    // CraftBukkit end

    // CraftBukkit start
    public boolean removeRecipe(ResourceKey<Recipe<?>> id) {
        boolean removed = this.recipes.removeRecipe(id);
        if (removed) {
            this.finalizeRecipeLoading();
        }

        return removed;
    }

    public void clearRecipes() {
        this.recipes = RecipeMap.createEmpty();
        this.finalizeRecipeLoading();
    }
    // CraftBukkit end
}
