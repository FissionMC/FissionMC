package net.fissionmc.testpluginlegacy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Runs every check* method of the ASM-generated class LegacyShapes (old-API bytecode shapes) and logs
 * "LEGACYCHECK <plugin> <name> OK|FAIL ...". Uses only reflection and Strings itself so that its own (possibly converted) bytecode
 * is trivial. Expected values depend on the variant (plugin.yml api-version): A = none, B = 1.13, C = 26.3.
 */
public final class LegacyMain extends JavaPlugin {
    @Override public void onEnable() {
        final String variant = getName();
        int fails = 0, total = 0;
        try {
            final Class<?> shapes = Class.forName("net.fissionmc.testpluginlegacy.LegacyShapes", true, getClass().getClassLoader());
            final Method[] ms = shapes.getDeclaredMethods();
            Arrays.sort(ms, Comparator.comparing(Method::getName));
            for (final Method m : ms) {
                if (!m.getName().startsWith("check")) continue;
                final String id = m.getName().substring(5);
                final String[] exp = expected(variant, id);
                if (exp == null) continue; // not applicable to this variant
                total++;
                String got;
                try { got = String.valueOf(m.invoke(null)); } catch (final java.lang.reflect.InvocationTargetException e) { got = "EXC " + e.getCause(); } catch (final Throwable t) { got = "EXC " + t; }
                final boolean ok = got.contains(exp[0]);
                if (!ok) fails++;
                getLogger().info("LEGACYCHECK " + variant + " " + id + (ok ? " OK " : " FAIL expected~" + exp[0] + " ") + "got=" + got);
            }
        } catch (final Throwable t) {
            fails++;
            getLogger().severe("LEGACYCHECK " + variant + " SETUP FAIL " + t);
        }
        getLogger().info("LEGACYCHECK " + variant + " DONE total=" + total + " fails=" + fails);
    }

    /** {substring the result must contain}, or null when the check does not apply to the variant. */
    private static String[] expected(final String v, final String id) {
        final boolean a = v.equals("LegacyA"), b = v.equals("LegacyB"), c = v.equals("LegacyC");
        switch (id) {
            case "MysqlDriver": return new String[] { c ? "com.mysql.jdbc.Driver" : "com.mysql.cj.jdbc.Driver" }; // ldc rewrite, all non-modern-API variants
            case "RenamedClassMount": return c ? null : new String[] { "org.bukkit.event.entity.EntityMountEvent" }; // class RENAMES (A and B)
            case "RenamedClassCow": return a ? new String[] { "org.bukkit.entity.AbstractCow" } : null; // only api older than ABSTRACT_COW
            case "MaterialFieldWool": return a ? new String[] { "WOOL" } : null; // GETSTATIC Material.WOOL -> LEGACY_WOOL, name() -> CraftLegacy.name
            case "MaterialGetMaterial": return a ? new String[] { "WOOL" } : null;
            case "MaterialValueOf": return a ? new String[] { "WOOD_DOOR" } : null;
            case "MaterialOrdinal": return a ? new String[] { "ok" } : null;
            case "MaterialValues": return a ? new String[] { "true" } : null; // legacy values() contains WOOL
            case "ItemStackShort": return a ? new String[] { "RED_WOOL" } : null; // WOOL:14 converted by CraftLegacy
            case "BlockGetTypeId": return a ? new String[] { "ok" } : null; // CraftEvil
            case "DyeColorSilver": return a ? new String[] { "LIGHT_GRAY" } : null; // field rename
            case "MaterialSignRename": return b ? new String[] { "OAK_SIGN" } : null; // modern=true field renames
            case "MaterialGrassRename": return b ? new String[] { "SHORT_GRASS" } : null;
            case "BiomeName": return c ? null : new String[] { "PLAINS" }; // EnumEvil: Biome is a registry-backed interface
            case "BiomeValueOf": return c ? null : new String[] { "PLAINS" };
            // EnumEvil is gated by settings.compatibility.enum-compatibility-mode, which Paper disables outright (CraftServer.loadCompatibilities "Big nope"): Enum.valueOf stays unrouted.
            case "EnumValueOfBiome": return c ? null : new String[] { "not an enum class" };
            // EnumMapBiome: upstream does not rewrite `new EnumMap` (Commodore.ENUM_RENAMES is unused) -> not checked.
            case "SoundValueOf": return c ? null : new String[] { "EXPERIENCE_ORB" };
            default: return null;
        }
    }
}
