package net.fissionmc.backend.mixin.world.entity.animal;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/animal/Animal.java.patch */
@Mixin(Animal.class)
public abstract class AnimalMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private @Nullable ItemStack breedItem; // CraftBukkit - Add breedItem variable
    // TODO(behaviour): setInLove(player, breedItemCopy) + EntityEnterLoveModeEvent; actuallyHurt(…, event) override;
    //  finalizeSpawnChildFromBreeding with EntityBreedEvent.
}
