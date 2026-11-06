import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

/**
 * Lists every field of every vanilla play packet (net.minecraft.network.protocol.game.Client|Serverbound*Packet) whose type can carry an entity id (int, int[], IntList,
 * Optional/OptionalInt, List of Integer, nested records/lists thereof), one line per field. Usage (no Gradle needed):
 *   java tools/EntityIdScan.java <server jar> <classpath of its libraries, ':' separated>
 * The output is the raw material of master/src/main/resources/entity-id-fields.txt: a person classifies each candidate once (ENTITY / NOT), the test
 * EntityIdTableTest checks that every candidate the scan finds is classified and that the ENTITY ones are really remapped by the rewriter.
 */
public class EntityIdScan {
    static String simple(Type t) { return t.getTypeName().replace("java.util.", "").replace("java.lang.", "").replace("it.unimi.dsi.fastutil.ints.", "").replace("net.minecraft.", "nm."); }
    static boolean intish(Type t) {
        String n = t.getTypeName();
        if (n.equals("int") || n.equals("java.lang.Integer") || n.equals("int[]") || n.endsWith("IntList") || n.endsWith("OptionalInt") || n.endsWith("IntArrayList") || n.endsWith("IntSet")) return true;
        if (t instanceof ParameterizedType p) { for (Type a : p.getActualTypeArguments()) if (intish(a)) return true; }
        return false;
    }
    public static void main(String[] a) throws Exception {
        List<URL> urls = new ArrayList<>(); urls.add(new File(a[0]).toURI().toURL());
        for (String s : a[1].split(":")) if (!s.isBlank()) urls.add(new File(s).toURI().toURL());
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), null);
        TreeSet<String> names = new TreeSet<>();
        try (ZipFile z = new ZipFile(a[0])) {
            for (var e = z.entries(); e.hasMoreElements();) { String n = e.nextElement().getName();
                if (n.startsWith("net/minecraft/network/protocol/game/") && n.endsWith("Packet.class") && !n.contains("$")) names.add(n.replace('/', '.').replace(".class", "")); }
        }
        for (String n : names) {
            Class<?> c;
            try { c = Class.forName(n, false, cl); } catch (Throwable t) { System.out.println("# cannot load " + n + " " + t); continue; }
            String dir = c.getSimpleName().startsWith("Clientbound") ? "C" : c.getSimpleName().startsWith("Serverbound") ? "S" : "?";
            List<String> parts = new ArrayList<>();
            if (c.isRecord()) { for (RecordComponent rc : c.getRecordComponents()) parts.add(rc.getName() + ":" + simple(rc.getGenericType()) + (intish(rc.getGenericType()) ? " *" : "")); }
            else for (Field f : c.getDeclaredFields()) { if (Modifier.isStatic(f.getModifiers())) continue; parts.add(f.getName() + ":" + simple(f.getGenericType()) + (intish(f.getGenericType()) ? " *" : "")); }
            System.out.println(dir + " " + c.getSimpleName() + " (" + String.join(", ", parts) + ")");
        }
    }
}
