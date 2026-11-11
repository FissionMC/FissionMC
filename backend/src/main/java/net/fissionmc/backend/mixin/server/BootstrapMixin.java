package net.fissionmc.backend.mixin.server;

import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/server/Bootstrap.java.patch
 * - CraftBukkit datafixer registrations used by CraftLegacy (legacy plugin material/block conversion).
 * - Paper entrypoint for bootstrapping (BOOTSTRAPPER providers).
 * (OptionallyFlatBedrockCondition::bootstrap is handled in BuiltInRegistriesMixin.)
 */
@Mixin(Bootstrap.class)
public abstract class BootstrapMixin {
    @Inject(method = "bootStrap()V", at = @At(value = "INVOKE", target = "Ljava/time/Instant;now()Ljava/time/Instant;", ordinal = 0, shift = At.Shift.AFTER))
    private static void paper$enterBootstrappers(final CallbackInfo ci) {
        io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler.enterBootstrappers(); // Paper - Entrypoint for bootstrapping
    }

    @Inject(method = "bootStrap()V", at = @At("TAIL"))
    private static void craftbukkit$legacyDataFixers(final CallbackInfo ci) {
            // CraftBukkit start
            // TODO Check what of this is needed, maybe report it to Mojira. if deemed relevant, move to the respective classes
            //  Used in CraftLegacy
            for (int i = 0; i <= 15; i++) {
                net.minecraft.util.datafix.fixes.BlockStateData.register(
                    1008 + i,
                    new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                        "Name", "minecraft:oak_sign",
                        "Properties", java.util.Map.of("rotation", String.valueOf(i))
                    )).convert(net.minecraft.nbt.NbtOps.INSTANCE),
                    new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                        "Name", "minecraft:standing_sign",
                        "Properties", java.util.Map.of("rotation", String.valueOf(i))
                    )).convert(net.minecraft.nbt.NbtOps.INSTANCE)
                );
            }

            net.minecraft.util.datafix.fixes.BlockStateData.register(1440,
                new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                    "Name", "minecraft:portal",
                    "Properties", java.util.Map.of("axis", "x")
                )).convert(net.minecraft.nbt.NbtOps.INSTANCE),
                new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                    "Name", "minecraft:portal",
                    "Properties", java.util.Map.of("axis", "x")
                )).convert(net.minecraft.nbt.NbtOps.INSTANCE)
            );

            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(409, "minecraft:prismarine_shard");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(410, "minecraft:prismarine_crystals");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(411, "minecraft:rabbit");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(412, "minecraft:cooked_rabbit");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(413, "minecraft:rabbit_stew");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(414, "minecraft:rabbit_foot");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(415, "minecraft:rabbit_hide");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(416, "minecraft:armor_stand");

            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(423, "minecraft:mutton");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(424, "minecraft:cooked_mutton");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(425, "minecraft:banner");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(426, "minecraft:end_crystal");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(427, "minecraft:spruce_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(428, "minecraft:birch_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(429, "minecraft:jungle_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(430, "minecraft:acacia_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(431, "minecraft:dark_oak_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(432, "minecraft:chorus_fruit");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(433, "minecraft:chorus_fruit_popped");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(434, "minecraft:beetroot");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(435, "minecraft:beetroot_seeds");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(436, "minecraft:beetroot_soup");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(437, "minecraft:dragon_breath");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(438, "minecraft:splash_potion");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(439, "minecraft:spectral_arrow");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(440, "minecraft:tipped_arrow");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(441, "minecraft:lingering_potion");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(442, "minecraft:shield");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(443, "minecraft:elytra");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(444, "minecraft:spruce_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(445, "minecraft:birch_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(446, "minecraft:jungle_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(447, "minecraft:acacia_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(448, "minecraft:dark_oak_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(449, "minecraft:totem_of_undying");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(450, "minecraft:shulker_shell");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(452, "minecraft:iron_nugget");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(453, "minecraft:knowledge_book");

            net.minecraft.util.datafix.fixes.ItemSpawnEggFix.ID_TO_ENTITY[23] = "Arrow";
            // CraftBukkit end
    }
}
