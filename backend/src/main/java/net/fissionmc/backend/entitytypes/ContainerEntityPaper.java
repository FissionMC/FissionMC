package net.fissionmc.backend.entitytypes;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jspecify.annotations.Nullable;

/**
 * Paper's versions of the {@link ContainerEntity} default methods
 * (sources/net/minecraft/world/entity/vehicle/ContainerEntity.java.patch). The implementing classes
 * (AbstractChestBoat, AbstractMinecartContainer) redirect their calls to the vanilla defaults here.
 */
public final class ContainerEntityPaper {
    private ContainerEntityPaper() {
    }

    public static void addChestVehicleSaveData(final ContainerEntity self, final ValueOutput output) {
        if (self.getContainerLootTable() != null) {
            output.putString("LootTable", self.getContainerLootTable().identifier().toString());
            self.lootableData().saveNbt(output); // Paper
            if (self.getContainerLootTableSeed() != 0L) {
                output.putLong("LootTableSeed", self.getContainerLootTableSeed());
            }
        }
        ContainerHelper.saveAllItems(output, self.getItemStacks()); // Paper - always save the items, table may still remain
    }

    public static void readChestVehicleSaveData(final ContainerEntity self, final ValueInput input) {
        self.clearItemStacks();
        ResourceKey<LootTable> lootTable = input.read("LootTable", LootTable.KEY_CODEC).orElse(null);
        self.setContainerLootTable(lootTable);
        self.setContainerLootTableSeed(input.getLongOr("LootTableSeed", 0L));
        // Paper start - LootTable API
        if (self.getContainerLootTable() != null) {
            self.lootableData().loadNbt(input);
        }
        // Paper end - LootTable API
        if (true || lootTable == null) { // Paper - always read the items, table may still remain
            ContainerHelper.loadAllItems(input, self.getItemStacks());
        }
    }

    public static InteractionResult interactWithContainerVehicle(final ContainerEntity self, final Player player) {
        // Paper start - Fix InventoryOpenEvent cancellation
        if (player.openMenu(self).isEmpty()) {
            return InteractionResult.PASS;
        }
        // Paper end - Fix InventoryOpenEvent cancellation
        return InteractionResult.SUCCESS;
    }

    public static void unpackChestVehicleLootTable(final ContainerEntity self, final @Nullable Player player) {
        MinecraftServer server = self.level().getServer();
        if (server != null && self.lootableData().shouldReplenish(self, com.destroystokyo.paper.loottable.PaperLootableInventoryData.ENTITY, player)) { // Paper - LootTable API
            LootTable lootTable = server.reloadableRegistries().getLootTable(self.getContainerLootTable());
            if (player != null) {
                CriteriaTriggers.GENERATE_LOOT.trigger((ServerPlayer) player, self.getContainerLootTable());
            }

            // Paper start - LootTable API
            if (self.lootableData().shouldClearLootTable(self, com.destroystokyo.paper.loottable.PaperLootableInventoryData.ENTITY, player)) {
                self.setContainerLootTable(null);
            }
            // Paper end - LootTable API
            LootParams.Builder builder = new LootParams.Builder((ServerLevel) self.level()).withParameter(LootContextParams.ORIGIN, self.position());
            if (player != null) {
                builder.withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);
            }

            lootTable.fill(self, builder.create(LootContextParamSets.CHEST), self.getContainerLootTableSeed());
        }
    }
}
