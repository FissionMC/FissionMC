package net.fissionmc.backend.mixin.world.item;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/item/ItemStack.java.patch */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Shadow @Final @Mutable private Holder<Item> item;
    @Shadow @Final @Mutable private PatchedDataComponentMap components;

    @Shadow public abstract DataComponentPatch getComponentsPatch();
    @Shadow public abstract void applyComponents(DataComponentPatch patch);

    // CraftBukkit start
    public void restorePatch(DataComponentPatch datacomponentpatch) {
        this.components.restorePatch(datacomponentpatch);
    }
    // CraftBukkit end

    // CraftBukkit start
    @Deprecated
    public void setItem(Item item) {
        this.item = item.builtInRegistryHolder();
        // Paper start - change base component prototype
        final DataComponentPatch patch = this.getComponentsPatch();
        this.components = new PatchedDataComponentMap(this.item.components());
        this.applyComponents(patch);
        // Paper end - change base component prototype
    }
    // CraftBukkit end
}
