package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/JukeboxBlockEntity.java.patch
 * TODO(behaviour): getMaxStackSize returns maxStack (vanilla body returns 1).
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {
    @Shadow private ItemStack item;
    @Shadow @Final private JukeboxSongPlayer jukeboxSongPlayer;

    // CraftBukkit start - add fields and methods
    private java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = net.minecraft.world.item.Item.ABSOLUTE_MAX_STACK_SIZE;
    // Surface: public (see surface/members/blocks.txt); Mixin requires non-public fields, the loader widens them.
    private boolean opened;

    public java.util.List<ItemStack> getContents() {
        return java.util.Collections.singletonList(this.item);
    }

    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
    }

    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    public org.bukkit.@Nullable Location getLocation() {
        final BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() == null) return null;
        return org.bukkit.craftbukkit.util.CraftLocation.toBukkit(self.getBlockPos(), self.getLevel());
    }
    // CraftBukkit end

    public void setSongItemWithoutPlaying(final ItemStack itemStack, final long ticksSinceSongStarted) { // CraftBukkit - passed ticks since song started
        final BlockEntity self = (BlockEntity) (Object) this;
        this.item = itemStack;
        this.jukeboxSongPlayer.song = null; // CraftBukkit - reset
        JukeboxSong.fromStack(itemStack).ifPresent(song -> this.jukeboxSongPlayer.setSongWithoutPlaying((Holder<JukeboxSong>) song, ticksSinceSongStarted)); // CraftBukkit - passed ticks since song started
        // CraftBukkit start - add null check for level
        if (self.getLevel() != null) {
            self.getLevel().updateNeighborsAt(self.getBlockPos(), self.getBlockState().getBlock());
        }
        // CraftBukkit end
        self.setChanged();
    }
}
