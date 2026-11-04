package net.fissionmc.backend.mixin.world.level.storage.loot;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/storage/loot/LootContext.java.patch */
@Mixin(LootContext.Builder.class)
public abstract class LootContext_BuilderMixin {
    @Shadow @Final private LootParams params;
    @Shadow private RandomSource random;

    // Paper start - bypass level for fake context
    @Deprecated
    public LootContext createWithoutLevel() {
        MinecraftServer server = MinecraftServer.getServer();
        RandomSource random = Optional.ofNullable(this.random).orElseThrow();
        return LootContextAccessorMixin.fission$new(this.params, random, server.reloadableRegistries().lookup());
    }
    // Paper end - bypass level for fake context
}
