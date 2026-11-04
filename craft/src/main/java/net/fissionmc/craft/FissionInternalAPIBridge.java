package net.fissionmc.craft;

/** Generated skeleton for io.papermc.paper.InternalAPIBridge; hand-edited from here on. Do not regenerate. */
public class FissionInternalAPIBridge implements io.papermc.paper.InternalAPIBridge {
    @Override public org.bukkit.block.Biome constructLegacyCustomBiome() { throw new UnsupportedOperationException("InternalAPIBridge#constructLegacyCustomBiome"); }
    @Override public io.papermc.paper.world.damagesource.CombatEntry createCombatEntry(org.bukkit.entity.LivingEntity a0, org.bukkit.damage.DamageSource a1, float a2) { throw new UnsupportedOperationException("InternalAPIBridge#createCombatEntry"); }
    @Override public io.papermc.paper.world.damagesource.CombatEntry createCombatEntry(org.bukkit.damage.DamageSource a0, float a1, io.papermc.paper.world.damagesource.FallLocationType a2, float a3) { throw new UnsupportedOperationException("InternalAPIBridge#createCombatEntry"); }
    @Override public java.util.function.Predicate<io.papermc.paper.command.brigadier.CommandSourceStack> restricted(java.util.function.Predicate<io.papermc.paper.command.brigadier.CommandSourceStack> a0, boolean a1) { throw new UnsupportedOperationException("InternalAPIBridge#restricted"); }
    @Override public io.papermc.paper.datacomponent.item.ResolvableProfile defaultMannequinProfile() { throw new UnsupportedOperationException("InternalAPIBridge#defaultMannequinProfile"); }
    @Override public com.destroystokyo.paper.SkinParts.Mutable allSkinParts() { throw new UnsupportedOperationException("InternalAPIBridge#allSkinParts"); }
    @Override public net.kyori.adventure.text.Component defaultMannequinDescription() { throw new UnsupportedOperationException("InternalAPIBridge#defaultMannequinDescription"); }
    @Override public <MODERN, LEGACY> org.bukkit.GameRule<LEGACY> legacyGameRuleBridge(org.bukkit.GameRule<MODERN> a0, java.util.function.Function<LEGACY, MODERN> a1, java.util.function.Function<MODERN, LEGACY> a2, java.lang.Class<LEGACY> a3) { throw new UnsupportedOperationException("InternalAPIBridge#legacyGameRuleBridge"); }
    @Override public java.util.Set<org.bukkit.entity.Pose> validMannequinPoses() { throw new UnsupportedOperationException("InternalAPIBridge#validMannequinPoses"); }
    @Override public io.papermc.paper.entity.poi.PoiType.Occupancy createOccupancy(java.lang.String a0) { throw new UnsupportedOperationException("InternalAPIBridge#createOccupancy"); }
    @Override public org.bukkit.damage.DamageSource.Builder createDamageSourceBuilder(org.bukkit.damage.DamageType a0) { throw new UnsupportedOperationException("InternalAPIBridge#createDamageSourceBuilder"); }
    @Override public org.bukkit.damage.DamageEffect getDamageEffect(java.lang.String a0) { throw new UnsupportedOperationException("InternalAPIBridge#getDamageEffect"); }
    @Override public java.lang.String getTranslationKey(org.bukkit.entity.EntityType a0) { throw new UnsupportedOperationException("InternalAPIBridge#getTranslationKey"); }
    @Override public org.bukkit.entity.SpawnCategory getSpawnCategory(org.bukkit.entity.EntityType a0) { throw new UnsupportedOperationException("InternalAPIBridge#getSpawnCategory"); }
    @Override public org.bukkit.inventory.ItemStack deserializeItem(byte[] a0) { throw new UnsupportedOperationException("InternalAPIBridge#deserializeItem"); }
    @Override public boolean hasDefaultEntityAttributes(org.bukkit.NamespacedKey a0) { throw new UnsupportedOperationException("InternalAPIBridge#hasDefaultEntityAttributes"); }
    @Override public org.bukkit.attribute.Attributable getDefaultEntityAttributes(org.bukkit.NamespacedKey a0) { throw new UnsupportedOperationException("InternalAPIBridge#getDefaultEntityAttributes"); }
    @Override public java.lang.String getStatisticCriteriaKey(org.bukkit.Statistic a0) { throw new UnsupportedOperationException("InternalAPIBridge#getStatisticCriteriaKey"); }
    @Override public io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager<org.bukkit.plugin.Plugin> createPluginLifecycleEventManager(org.bukkit.plugin.java.JavaPlugin a0, java.util.function.BooleanSupplier a1) { return new io.papermc.paper.plugin.lifecycle.event.PaperLifecycleEventManager<>(a0, a1); }
    @Override public org.bukkit.inventory.ItemStack createEmptyStack() { throw new UnsupportedOperationException("InternalAPIBridge#createEmptyStack"); }
    @Override public net.kyori.adventure.text.Component resolveWithContext(net.kyori.adventure.text.Component a0, org.bukkit.command.CommandSender a1, org.bukkit.entity.Entity a2, boolean a3) throws java.io.IOException { throw new UnsupportedOperationException("InternalAPIBridge#resolveWithContext"); }
    @Override public net.kyori.adventure.text.flattener.ComponentFlattener componentFlattener() { throw new UnsupportedOperationException("InternalAPIBridge#componentFlattener"); }
    @Override public <C> io.papermc.paper.persistence.PersistentDataKey<C> createPersistentDataKey(net.kyori.adventure.key.Key a0, org.bukkit.persistence.PersistentDataType<?, C> a1) { throw new UnsupportedOperationException("InternalAPIBridge#createPersistentDataKey"); }
    @Override public org.bukkit.enchantments.EnchantmentOffer[] rollEnchantmentOffers(org.bukkit.inventory.ItemStack a0, int a1, int a2, int a3) { throw new UnsupportedOperationException("InternalAPIBridge#rollEnchantmentOffers"); }
}
