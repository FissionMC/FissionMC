package net.fissionmc.backend.blocks;

import java.util.List;
import net.fissionmc.backend.relay.ItemTransferBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Stand-in for a container in a chunk another backend owns: always empty, forwards inserted items to the owner. */
public final class GhostContainer implements Container {
    private final ServerLevel level;
    private final BlockPos pos;

    public GhostContainer(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos.immutable();
    }

    @Override public int getContainerSize() { return 1; }
    @Override public boolean isEmpty() { return true; }
    @Override public ItemStack getItem(int slot) { return ItemStack.EMPTY; }
    @Override public ItemStack removeItem(int slot, int count) { return ItemStack.EMPTY; }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!stack.isEmpty()) ItemTransferBackend.send(this.level, this.pos, stack.copy());
    }

    @Override public int getMaxStackSize() { return 64; }
    @Override public void setChanged() { }
    @Override public boolean stillValid(Player player) { return false; }
    @Override public void clearContent() { }

    @Override public List<ItemStack> getContents() { return List.of(); }
    @Override public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) { }
    @Override public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) { }
    @Override public List<org.bukkit.entity.HumanEntity> getViewers() { return List.of(); }
    @Override public org.bukkit.inventory.InventoryHolder getOwner() { return null; }
    @Override public void setMaxStackSize(int size) { }
    @Override public org.bukkit.Location getLocation() { return null; }
}
