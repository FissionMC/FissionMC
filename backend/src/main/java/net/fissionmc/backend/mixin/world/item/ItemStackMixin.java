package net.fissionmc.backend.mixin.world.item;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import java.util.function.Consumer;

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

    @Shadow public abstract void hurtAndBreak(int amount, ServerLevel level, ServerPlayer player, Consumer<ItemStack> onBreak);

    // Paper start - add param to skip infinite mats check
    public void hurtAndBreak(final int amount, final LivingEntity owner, final EquipmentSlot slot, final boolean force) {
        // Paper end - add param to skip infinite mats check
        if (owner.level() instanceof ServerLevel serverLevel) {
            // TODO(behaviour): Paper passes the LivingEntity owner and `force` into hurtAndBreak(..., force), which fires
            // PlayerItemDamageEvent/EntityDamageItemEvent and skips the infinite-materials check when forced.
            this.hurtAndBreak(
                amount, serverLevel, owner instanceof ServerPlayer player ? player : null, brokenItem -> {if (slot != null) owner.onEquippedItemBroken(brokenItem, slot); } // Paper - Add EntityDamageItemEvent & itemstack damage API - do not process entity related callbacks when damaging from API
            );
        }
    }
}
