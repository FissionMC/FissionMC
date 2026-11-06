import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

/**
 * Lists every synched entity data accessor (SynchedEntityData.defineId) of every vanilla entity class whose serializer can carry an entity id: INT and
 * OPTIONAL_UNSIGNED_INT (and, for completeness, OPTIONAL_LIVING_ENTITY_REFERENCE / ENTITY_REFERENCE style ones if they exist in this version). A person classifies each
 * line once (see master/src/main/resources/entity-id-fields.txt). Usage (no Gradle): java tools/EntityDataScan.java <server jar> <libraries classpath>
 */
public class EntityDataScan {
    public static void main(String[] a) throws Exception {
        List<URL> urls = new ArrayList<>(); urls.add(new File(a[0]).toURI().toURL());
        for (String s : a[1].split(":")) if (!s.isBlank()) urls.add(new File(s).toURI().toURL());
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), EntityDataScan.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        Class.forName("net.minecraft.SharedConstants", true, cl).getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap", true, cl).getMethod("bootStrap").invoke(null);
        Class<?> serializers = Class.forName("net.minecraft.network.syncher.EntityDataSerializers", true, cl);
        Class<?> accessorC = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", true, cl);
        Method ser = accessorC.getMethod("serializer");
        Map<Object, String> wanted = new IdentityHashMap<>();
        for (Field f : serializers.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName();
            if (n.equals("INT") || n.contains("UNSIGNED_INT") || n.contains("ENTITY") || n.contains("REFERENCE")) { f.setAccessible(true); wanted.put(f.get(null), n); }
        }
        System.out.println("# serializers considered: " + new TreeSet<>(wanted.values()));
        // numeric ids (registry order, what the wire carries) of the entity types whose class is a Projectile: their AddEntity `data` is the owner's entity id
        Class<?> projC = Class.forName("net.minecraft.world.entity.projectile.Projectile", true, cl);
        Class<?> typesC = Class.forName("net.minecraft.world.entity.EntityTypes", true, cl);
        Class<?> regs = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, cl);
        Object reg = regs.getField("ENTITY_TYPE").get(null);
        Method getId = null; for (Method m : reg.getClass().getMethods()) if (m.getName().equals("getId") && m.getParameterCount() == 1 && !m.getParameterTypes()[0].isPrimitive()) { getId = m; break; }
        List<String> projectiles = new ArrayList<>();
        for (Field f : typesC.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !(f.getGenericType() instanceof ParameterizedType p) || !p.getRawType().getTypeName().endsWith(".EntityType")) continue;
            Type arg = p.getActualTypeArguments()[0]; String raw = arg instanceof ParameterizedType pp ? pp.getRawType().getTypeName() : arg.getTypeName();
            f.setAccessible(true); Object tv = f.get(null); int nid = (Integer) getId.invoke(reg, tv);
            System.out.println("ENTITY_TYPE " + nid + " " + f.getName().toLowerCase() + " " + raw.replace("net.minecraft.world.entity.", ""));
            if (projC.isAssignableFrom(Class.forName(raw, false, cl))) projectiles.add(nid + " " + f.getName().toLowerCase());
        }
        System.out.println("PROJECTILE_ENTITY_TYPES " + String.join(",", projectiles));
        TreeSet<String> names = new TreeSet<>();
        try (ZipFile z = new ZipFile(a[0])) {
            for (var e = z.entries(); e.hasMoreElements();) { String n = e.nextElement().getName();
                if (n.startsWith("net/minecraft/world/entity/") && n.endsWith(".class")) names.add(n.replace('/', '.').replace(".class", "")); }
        }
        for (String n : names) {
            Class<?> c;
            try { c = Class.forName(n, true, cl); } catch (Throwable t) { continue; }
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !accessorC.isAssignableFrom(f.getType())) continue;
                try { f.setAccessible(true); Object acc = f.get(null); if (acc == null) continue; String s = wanted.get(ser.invoke(acc));
                    if (s != null) System.out.println("DATA " + c.getName().replace("net.minecraft.world.entity.", "") + "." + f.getName() + " " + s + " " + acc.getClass().getMethod("id").invoke(acc));
                } catch (Throwable t) { System.out.println("# " + c.getName() + "." + f.getName() + " " + t); }
            }
        }
    }
}
