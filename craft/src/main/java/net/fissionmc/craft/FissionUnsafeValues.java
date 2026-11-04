package net.fissionmc.craft;

/** Generated skeleton for org.bukkit.UnsafeValues; hand-edited from here on. Do not regenerate. */
public class FissionUnsafeValues implements org.bukkit.UnsafeValues {
    @Override public org.bukkit.Material toLegacy(org.bukkit.Material a0) { throw new UnsupportedOperationException("UnsafeValues#toLegacy"); }
    @Override public org.bukkit.Material fromLegacy(org.bukkit.Material a0) { throw new UnsupportedOperationException("UnsafeValues#fromLegacy"); }
    @Override public org.bukkit.Material fromLegacy(org.bukkit.material.MaterialData a0) { throw new UnsupportedOperationException("UnsafeValues#fromLegacy"); }
    @Override public org.bukkit.Material fromLegacy(org.bukkit.material.MaterialData a0, boolean a1) { throw new UnsupportedOperationException("UnsafeValues#fromLegacy"); }
    @Override public org.bukkit.block.data.BlockData fromLegacy(org.bukkit.Material a0, byte a1) { throw new UnsupportedOperationException("UnsafeValues#fromLegacy"); }
    @Override public org.bukkit.Material getMaterial(java.lang.String a0, int a1) { throw new UnsupportedOperationException("UnsafeValues#getMaterial"); }
    @Override public int getDataVersion() { throw new UnsupportedOperationException("UnsafeValues#getDataVersion"); }
    @Override public org.bukkit.inventory.ItemStack modifyItemStack(org.bukkit.inventory.ItemStack a0, java.lang.String a1) { throw new UnsupportedOperationException("UnsafeValues#modifyItemStack"); }
    @Override public void checkSupported(org.bukkit.plugin.PluginDescriptionFile a0) { throw new UnsupportedOperationException("UnsafeValues#checkSupported"); }
    @Override public byte[] processClass(org.bukkit.plugin.PluginDescriptionFile a0, java.lang.String a1, byte[] a2) { throw new UnsupportedOperationException("UnsafeValues#processClass"); }
    @Override public org.bukkit.advancement.Advancement loadAdvancement(net.kyori.adventure.key.Key a0, java.lang.String a1, boolean a2) { throw new UnsupportedOperationException("UnsafeValues#loadAdvancement"); }
    @Override public java.util.List<org.bukkit.advancement.Advancement> loadAdvancements(java.util.Map<net.kyori.adventure.key.Key, java.lang.String> a0, boolean a1) { throw new UnsupportedOperationException("UnsafeValues#loadAdvancements"); }
    @Override public boolean removeAdvancement(org.bukkit.NamespacedKey a0) { throw new UnsupportedOperationException("UnsafeValues#removeAdvancement"); }
    @Override public org.bukkit.potion.PotionType.InternalPotionData getInternalPotionData(org.bukkit.NamespacedKey a0) { throw new UnsupportedOperationException("UnsafeValues#getInternalPotionData"); }
    @Override public java.lang.String get(java.lang.Class<?> a0, java.lang.String a1) { throw new UnsupportedOperationException("UnsafeValues#get"); }
    @Override public <B extends org.bukkit.Keyed> B get(io.papermc.paper.registry.RegistryKey<B> a0, org.bukkit.NamespacedKey a1) { throw new UnsupportedOperationException("UnsafeValues#get"); }
    @Override public boolean isSupportedApiVersion(java.lang.String a0) { throw new UnsupportedOperationException("UnsafeValues#isSupportedApiVersion"); }
    @Override public com.google.gson.JsonObject serializeItemAsJson(org.bukkit.inventory.ItemStack a0) { throw new UnsupportedOperationException("UnsafeValues#serializeItemAsJson"); }
    @Override public org.bukkit.inventory.ItemStack deserializeItemFromJson(com.google.gson.JsonObject a0) { throw new UnsupportedOperationException("UnsafeValues#deserializeItemFromJson"); }
    @Override public byte[] serializeEntity(org.bukkit.entity.Entity a0, io.papermc.paper.entity.EntitySerializationFlag... a1) { throw new UnsupportedOperationException("UnsafeValues#serializeEntity"); }
    @Override public org.bukkit.entity.Entity deserializeEntity(byte[] a0, org.bukkit.World a1, boolean a2, boolean a3) { throw new UnsupportedOperationException("UnsafeValues#deserializeEntity"); }
    @Override public int nextEntityId(org.bukkit.World a0) { throw new UnsupportedOperationException("UnsafeValues#nextEntityId"); }
    @Override public java.lang.String getMainLevelName() { throw new UnsupportedOperationException("UnsafeValues#getMainLevelName"); }
    @Override public int getProtocolVersion() { throw new UnsupportedOperationException("UnsafeValues#getProtocolVersion"); }
    @Override public org.bukkit.inventory.ItemStack deserializeStack(java.util.Map<java.lang.String, java.lang.Object> a0) { throw new UnsupportedOperationException("UnsafeValues#deserializeStack"); }
    @Override public org.bukkit.inventory.ItemStack deserializeItemHover(net.kyori.adventure.text.event.HoverEvent.ShowItem a0) { throw new UnsupportedOperationException("UnsafeValues#deserializeItemHover"); }
}
