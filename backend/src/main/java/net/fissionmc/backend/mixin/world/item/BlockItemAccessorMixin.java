package net.fissionmc.backend.mixin.world.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accessor for BlockItem#getPlaceSound (protected; Paper calls it from ItemStack in the same package). */
@Mixin(BlockItem.class)
public interface BlockItemAccessorMixin {
    @Invoker("getPlaceSound")
    SoundEvent fission$getPlaceSound(BlockState state);
}
