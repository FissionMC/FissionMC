package net.fissionmc.backend.mixin.world.level.storage.loot;

import net.minecraft.core.HolderGetter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** FissionMC: access to LootContext's private constructor for LootContext_BuilderMixin#createWithoutLevel. */
@Mixin(LootContext.class)
public interface LootContextAccessorMixin {
    @Invoker("<init>")
    static LootContext fission$new(LootParams params, RandomSource random, HolderGetter.Provider lootDataResolver) {
        throw new AssertionError();
    }
}
