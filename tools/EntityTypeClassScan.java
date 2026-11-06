import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/**
 * Maps every vanilla entity type (fields of net.minecraft.world.entity.EntityTypes, EntityType<X>) to its entity class X and flags the Projectiles: for those the
 * `data` int of ClientboundAddEntityPacket is the OWNER'S ENTITY ID (see AddEntityDataScan) and must be mapped by the id rewriter; for every other type it is not an entity id.
 * Usage: java tools/EntityTypeClassScan.java <server jar> <libraries classpath>
 */
public class EntityTypeClassScan {
    public static void main(String[] a) throws Exception {
        List<URL> urls = new ArrayList<>(); urls.add(new File(a[0]).toURI().toURL());
        for (String s : a[1].split(":")) if (!s.isBlank()) urls.add(new File(s).toURI().toURL());
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), null);
        Class<?> proj = Class.forName("net.minecraft.world.entity.projectile.Projectile", false, cl);
        Class<?> types = Class.forName("net.minecraft.world.entity.EntityTypes", false, cl);
        for (Field f : types.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !(f.getGenericType() instanceof ParameterizedType p)) continue;
            if (!p.getRawType().getTypeName().equals("net.minecraft.world.entity.EntityType")) continue;
            Type arg = p.getActualTypeArguments()[0];
            String raw = arg instanceof ParameterizedType pp ? pp.getRawType().getTypeName() : arg.getTypeName();
            boolean isProj = false;
            try { isProj = proj.isAssignableFrom(Class.forName(raw, false, cl)); } catch (Throwable t) { }
            System.out.println(f.getName().toLowerCase() + " " + raw.replace("net.minecraft.world.entity.", "") + (isProj ? " PROJECTILE" : ""));
        }
    }
}
