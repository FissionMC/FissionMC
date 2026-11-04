package net.fissionmc.backend.mixin.world.inventory;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.IdMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/inventory/EnchantmentMenu.java.patch
 * TODO(behaviour): slotsChanged uses fillEnchantmentOffers + PrepareItemEnchantEvent, clickMenuButton fires EnchantItemEvent.
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {
    @Shadow @Final private DataSlot enchantmentSeed;

    // Paper start - add enchantment seed update API
    public void setEnchantmentSeed(int seed) {
        this.enchantmentSeed.set(seed);
    }
    // Paper end - add enchantment seed update API

    // Paper start - extract offer generation
    // Extracted over modifications in place given nearly every line has diff to account for non-instance fields for
    // cost, enchantment and level clues.
    // Surface: public static (see surface/members/blocks.txt); Mixin requires non-public statics, the loader widens it.
    private static void fillEnchantmentOffers(final RandomSource random, final int seed, final int bookcases, final ItemStack itemStack, final RegistryAccess registryAccess, final int[] costs, final int[] enchantClue, final int[] levelClue) {
        random.setSeed(seed);

        for (int ixx = 0; ixx < costs.length; ixx++) {
            costs[ixx] = EnchantmentHelper.getEnchantmentCost(random, ixx, bookcases, itemStack);
            enchantClue[ixx] = -1;
            levelClue[ixx] = -1;
            if (costs[ixx] < ixx + 1) {
                costs[ixx] = 0;
            }
        }

        final IdMap<Holder<Enchantment>> holders = registryAccess.lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();

        for (int ix = 0; ix < costs.length; ix++) {
            if (costs[ix] > 0) {
                List<EnchantmentInstance> list = getEnchantmentList(registryAccess, itemStack, ix, costs[ix], random, seed);
                if (!list.isEmpty()) {
                    EnchantmentInstance ench = list.get(random.nextInt(list.size()));
                    enchantClue[ix] = holders.getId(ench.enchantment());
                    levelClue[ix] = ench.level();
                }
            }
        }
    }
    // Paper end - extract offer generation

    // Paper start - make static and public
    private static List<EnchantmentInstance> getEnchantmentList(final RegistryAccess access, final ItemStack itemStack, final int slot, final int enchantmentCost, final RandomSource random, final int seed) {
        random.setSeed(seed + slot);
        // Paper end - make static and public
        Optional<HolderSet.Named<Enchantment>> tag = access.lookupOrThrow(Registries.ENCHANTMENT).get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (tag.isEmpty()) {
            return List.of();
        }

        List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(random, itemStack, enchantmentCost, tag.get().stream()); // Paper - make static and public
        if (itemStack.is(Items.BOOK) && list.size() > 1) {
            list.remove(random.nextInt(list.size())); // Paper - make static and public
        }

        return list;
    }
}
