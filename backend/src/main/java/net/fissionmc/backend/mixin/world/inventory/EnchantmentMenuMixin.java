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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;

/**
 * Paper: sources/net/minecraft/world/inventory/EnchantmentMenu.java.patch
 * TODO(behaviour): Add missing InventoryHolders (enchantSlots with block holder / location).
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin extends AbstractContainerMenu {
    @Shadow @Final private DataSlot enchantmentSeed;
    @Shadow @Final private Container enchantSlots;
    @Shadow @Final private ContainerLevelAccess access;
    @Shadow @Final private RandomSource random;
    @Shadow @Final public int[] costs;
    @Shadow @Final public int[] enchantClue;
    @Shadow @Final public int[] levelClue;
    @Shadow private List<EnchantmentInstance> getEnchantmentList(final RegistryAccess access, final ItemStack itemStack, final int slot, final int enchantmentCost) { throw new AssertionError(); }

    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.@org.jspecify.annotations.Nullable CraftEnchantmentView view = null;
    private org.bukkit.entity.Player player;
    // CraftBukkit end

    private EnchantmentMenuMixin() {
        super(null, 0);
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("RETURN"))
    private void fission$savePlayer(final int containerId, final Inventory inventory, final ContainerLevelAccess access, final CallbackInfo ci) {
        this.player = (org.bukkit.entity.Player) inventory.player.getBukkitEntity(); // CraftBukkit
    }

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void fission$checkReachable(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.checkReachable) cir.setReturnValue(true); // CraftBukkit
    }

    // CraftBukkit start
    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftEnchantmentView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryEnchanting inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryEnchanting(this.enchantSlots);
        this.view = new org.bukkit.craftbukkit.inventory.view.CraftEnchantmentView(this.player, inventory, (EnchantmentMenu) (Object) this);
        return this.view;
    }
    // CraftBukkit end

    // CraftBukkit - PrepareItemEnchantEvent (replaced body)
    @Inject(method = "slotsChanged", at = @At("HEAD"), cancellable = true)
    private void fission$slotsChanged(final Container container, final CallbackInfo ci) {
        ci.cancel();
        if (container == this.enchantSlots) {
            ItemStack itemStack = container.getItem(0);
            if (!itemStack.isEmpty()) { // CraftBukkit - relax condition
                this.access.execute((level, pos) -> {
                    IdMap<Holder<Enchantment>> holders = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
                    int bookcases = 0;

                    for (BlockPos offset : net.minecraft.world.level.block.EnchantingTableBlock.BOOKSHELF_OFFSETS) {
                        if (net.minecraft.world.level.block.EnchantingTableBlock.isValidBookShelf(level, pos, offset)) {
                            bookcases++;
                        }
                    }

                    EnchantmentMenu.fillEnchantmentOffers(this.random, this.enchantmentSeed.get(), bookcases, itemStack, level.registryAccess(), this.costs, this.enchantClue, this.levelClue); // Paper - extracted into net.minecraft.world.inventory.EnchantmentMenu.fillEnchantmentOffers
                    // CraftBukkit start
                    org.bukkit.inventory.ItemStack craftItemStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemStack);
                    org.bukkit.enchantments.EnchantmentOffer[] offers = new org.bukkit.enchantments.EnchantmentOffer[3];
                    for (int j = 0; j < 3; ++j) {
                        org.bukkit.enchantments.Enchantment enchantment = (this.enchantClue[j] >= 0) ? org.bukkit.craftbukkit.enchantments.CraftEnchantment.minecraftHolderToBukkit(holders.byId(this.enchantClue[j])) : null;
                        offers[j] = (enchantment != null) ? new org.bukkit.enchantments.EnchantmentOffer(enchantment, this.levelClue[j], this.costs[j]) : null;
                    }

                    org.bukkit.event.enchantment.PrepareItemEnchantEvent event = new org.bukkit.event.enchantment.PrepareItemEnchantEvent(this.player, this.getBukkitView(), this.access.getLocation().getBlock(), craftItemStack, offers, bookcases);
                    event.setCancelled(!itemStack.isEnchantable());
                    level.getCraftServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        for (int j = 0; j < 3; ++j) {
                            this.costs[j] = 0;
                            this.enchantClue[j] = -1;
                            this.levelClue[j] = -1;
                        }
                        return;
                    }

                    for (int j = 0; j < 3; j++) {
                        org.bukkit.enchantments.EnchantmentOffer offer = event.getOffers()[j];
                        if (offer != null) {
                            this.costs[j] = offer.getCost();
                            this.enchantClue[j] = holders.getId(org.bukkit.craftbukkit.enchantments.CraftEnchantment
                                .bukkitToMinecraftHolder(offer.getEnchantment()));
                            this.levelClue[j] = offer.getEnchantmentLevel();
                        } else {
                            if (enchantClue[j] != -1) this.costs[j] = 0;
                            this.enchantClue[j] = -1;
                            this.levelClue[j] = -1;
                        }
                    }
                    // CraftBukkit end

                    this.broadcastChanges();
                });
            } else {
                for (int i = 0; i < 3; i++) {
                    this.costs[i] = 0;
                    this.enchantClue[i] = -1;
                    this.levelClue[i] = -1;
                }
            }
        }
    }

    // CraftBukkit - EnchantItemEvent (replaced lambda body of clickMenuButton)
    @Inject(method = "lambda$clickMenuButton$0", at = @At("HEAD"), cancellable = true)
    private void fission$enchant(final ItemStack itemStack, final int buttonId, final Player player, final int enchantmentCost, final ItemStack currency, final Level level, final BlockPos pos, final CallbackInfo ci) {
        ci.cancel();
        ItemStack enchantmentItem = itemStack; // Paper - diff on change
        List<EnchantmentInstance> newEnchantment = this.getEnchantmentList(level.registryAccess(), enchantmentItem, buttonId, this.costs[buttonId]);
        // CraftBukkit start
        IdMap<Holder<Enchantment>> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
        if (true || !newEnchantment.isEmpty()) {
            // player.onEnchantmentPerformed(enchantmentItem, enchantmentCost); // Moved down
            java.util.Map<org.bukkit.enchantments.Enchantment, Integer> enchants = new java.util.HashMap<>();
            for (EnchantmentInstance instance : newEnchantment) {
                enchants.put(org.bukkit.craftbukkit.enchantments.CraftEnchantment.minecraftHolderToBukkit(instance.enchantment()), instance.level());
            }
            org.bukkit.inventory.ItemStack craftItemStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(enchantmentItem);
            Holder<Enchantment> holder = registry.byId(this.enchantClue[buttonId]);
            if (holder == null) return;
            org.bukkit.enchantments.Enchantment hintedEnchantment = org.bukkit.craftbukkit.enchantments.CraftEnchantment.minecraftHolderToBukkit(holder);
            int hintedEnchantmentLevel = this.levelClue[buttonId];
            org.bukkit.event.enchantment.EnchantItemEvent event = new org.bukkit.event.enchantment.EnchantItemEvent((org.bukkit.entity.Player) player.getBukkitEntity(), this.getBukkitView(), this.access.getLocation().getBlock(), craftItemStack, this.costs[buttonId], enchants, hintedEnchantment, hintedEnchantmentLevel, buttonId);
            level.getCraftServer().getPluginManager().callEvent(event);
            int itemLevel = event.getExpLevelCost();
            if (event.isCancelled() || (itemLevel > player.experienceLevel && !player.getAbilities().instabuild) || event.getEnchantsToAdd().isEmpty()) {
                return;
            }
            // CraftBukkit end
            // Paper start
            enchantmentItem = org.bukkit.craftbukkit.inventory.CraftItemStack.getOrCloneOnMutation(craftItemStack, event.getItem());
            if (enchantmentItem != itemStack) {
                this.enchantSlots.setItem(0, enchantmentItem);
            }
            if (enchantmentItem.is(Items.BOOK)) {
                enchantmentItem = enchantmentItem.transmuteCopy(Items.ENCHANTED_BOOK);
                this.enchantSlots.setItem(0, enchantmentItem);
            }
            // Paper end

            // CraftBukkit start
            for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : event.getEnchantsToAdd().entrySet()) {
                Holder<Enchantment> enchantment = org.bukkit.craftbukkit.enchantments.CraftEnchantment.bukkitToMinecraftHolder(entry.getKey());
                if (enchantment == null) {
                    continue;
                }

                enchantmentItem.enchant(enchantment, entry.getValue());
            }
            player.onEnchantmentPerformed(enchantmentItem, enchantmentCost);
            // CraftBukkit end

            // CraftBukkit - TODO: let plugins change this
            currency.consume(enchantmentCost, player);
            if (currency.isEmpty()) {
                this.enchantSlots.setItem(1, ItemStack.EMPTY);
            }

            player.awardStat(net.minecraft.stats.Stats.ENCHANT_ITEM);
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.minecraft.advancements.triggers.CriteriaTriggers.ENCHANTED_ITEM.trigger(serverPlayer, enchantmentItem, enchantmentCost);
            }

            this.enchantSlots.setChanged();
            this.enchantmentSeed.set(player.getEnchantmentSeed());
            this.slotsChanged(this.enchantSlots);
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ENCHANTMENT_TABLE_USE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
        }
    }

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
