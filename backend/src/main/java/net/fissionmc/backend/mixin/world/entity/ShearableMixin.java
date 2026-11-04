package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Members Paper adds to {@link Shearable} (paper-server/patches/sources/net/minecraft/world/entity/Shearable.java.patch).
 * Paper declares {@code Level level()} abstract (implemented by every Shearable entity); Mixin does not add abstract interface
 * methods, so FissionMC adds it as a default that the implementors' Entity#level() always overrides.
 */
@Mixin(Shearable.class)
public interface ShearableMixin {
    default void shear(ServerLevel level, SoundSource soundSource, ItemStack tool, java.util.List<net.minecraft.world.item.ItemStack> drops) { ((Shearable) this).shear(level, soundSource, tool); } // Paper - Add drops to shear events // TODO(behaviour): implementors override with drops

    default net.minecraft.world.level.Level level() { return ((net.minecraft.world.entity.Entity) (Object) this).level(); } // Shearable API - expose default level needed for shearing. // FissionMC: default instead of abstract, see class comment

    // Paper start - custom shear drops; ensure all implementing entities override this
    default java.util.List<net.minecraft.world.item.ItemStack> generateDefaultDrops(final ServerLevel level, final ItemStack tool) {
        return java.util.Collections.emptyList();
    }
    // Paper end - custom shear drops
}
