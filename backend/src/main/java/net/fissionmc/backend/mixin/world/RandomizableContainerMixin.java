package net.fissionmc.backend.mixin.world;

import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/world/RandomizableContainer.java.patch
 * tryLoadLootTable/trySaveLootTable/unpackLootTable consult lootableData() (LootTable API), see the injectors below.
 */
@Mixin(RandomizableContainer.class)
public interface RandomizableContainerMixin {
    // Paper start - LootTable API
    default void unpackLootTable(@Nullable final Player player, final boolean forceClearLootTable) {
        // Paper moves the vanilla body here (vanilla unpackLootTable(Player) delegates, see paper$unpackLootTable)
        final RandomizableContainer self = (RandomizableContainer) this;
        net.minecraft.world.level.Level level = self.getLevel();
        net.minecraft.core.BlockPos worldPosition = self.getBlockPos();
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> lootTableKey = self.getLootTable();
        // Paper start - LootTable API
        lootReplenish: if (lootTableKey != null && level != null && level.getServer() != null) {
            if (self.lootableData() != null && !self.lootableData().shouldReplenish(self, com.destroystokyo.paper.loottable.PaperLootableInventoryData.CONTAINER, player)) {
                if (forceClearLootTable) {
                    self.setLootTable(null);
                }
                break lootReplenish;
            }
            // Paper end - LootTable API
            net.minecraft.world.level.storage.loot.LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(lootTableKey);
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.minecraft.advancements.triggers.CriteriaTriggers.GENERATE_LOOT.trigger(serverPlayer, lootTableKey);
            }

            if (forceClearLootTable || self.lootableData() == null || self.lootableData().shouldClearLootTable(self, com.destroystokyo.paper.loottable.PaperLootableInventoryData.CONTAINER, player)) { // Paper - LootTable API
                self.setLootTable(null);
            } // Paper - LootTable API
            net.minecraft.world.level.storage.loot.LootParams.Builder params = new net.minecraft.world.level.storage.loot.LootParams.Builder((net.minecraft.server.level.ServerLevel) level).withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.atCenterOf(worldPosition));
            if (player != null) {
                params.withLuck(player.getLuck()).withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, player);
            }

            lootTable.fill(self, params.create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST), self.getLootTableSeed());
        }
    }

    // Paper - vanilla unpackLootTable(Player) delegates to the 2-arg version
    @Inject(method = "unpackLootTable(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"), cancellable = true)
    private void paper$unpackLootTable(@Nullable final Player player, final CallbackInfo ci) {
        ci.cancel();
        this.unpackLootTable(player, false);
    }

    // Paper - only track the loot table if there is chance for replenish
    @Inject(method = "tryLoadLootTable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/RandomizableContainer;setLootTableSeed(J)V"))
    private void paper$loadLootableData(final net.minecraft.world.level.storage.ValueInput base, final CallbackInfoReturnable<Boolean> cir,
                                        @Local final net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> lootTable) {
        final RandomizableContainer self = (RandomizableContainer) this;
        if (self.lootableData() != null && lootTable != null) self.lootableData().loadNbt(base); // Paper - LootTable API
    }

    @ModifyReturnValue(method = "tryLoadLootTable", at = @At("RETURN"))
    private boolean paper$tryLoadResult(final boolean tracked) {
        return tracked && ((RandomizableContainer) this).lootableData() == null; // Paper - only track the loot table if there is chance for replenish
    }

    @Inject(method = "trySaveLootTable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/RandomizableContainer;getLootTableSeed()J"))
    private void paper$saveLootableData(final net.minecraft.world.level.storage.ValueOutput base, final CallbackInfoReturnable<Boolean> cir) {
        final RandomizableContainer self = (RandomizableContainer) this;
        if (self.lootableData() != null) self.lootableData().saveNbt(base); // Paper - LootTable API
    }

    @ModifyReturnValue(method = "trySaveLootTable", at = @At("RETURN"))
    private boolean paper$trySaveResult(final boolean tracked) {
        return tracked && ((RandomizableContainer) this).lootableData() == null; // Paper - only track the loot table if there is chance for replenish
    }

    default com.destroystokyo.paper.loottable.@Nullable PaperLootableInventoryData lootableData() {
        return null; // some containers don't really have a "replenish" ability like decorated pots
    }

    default com.destroystokyo.paper.loottable.PaperLootableInventory getLootableInventory() {
        final RandomizableContainer self = (RandomizableContainer) this;
        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(java.util.Objects.requireNonNull(self.getLevel(), "Cannot manage loot tables on block entities not in world"), self.getBlockPos());
        return (com.destroystokyo.paper.loottable.PaperLootableInventory) block.getState(false);
    }
    // Paper end - LootTable API
}
