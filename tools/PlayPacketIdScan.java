import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/**
 * Prints the REAL vanilla 26.3 play packet ids: GameProtocols.CLIENTBOUND_TEMPLATE / SERVERBOUND_TEMPLATE .details().listPackets((type, id) -> ...).
 * Output: `C <id> <PacketType name>` (e.g. `C 1 minecraft:add_entity`). Usage: java tools/PlayPacketIdScan.java <server jar> <libraries classpath>
 */
public class PlayPacketIdScan {
    public static void main(String[] a) throws Exception {
        List<URL> urls = new ArrayList<>(); urls.add(new File(a[0]).toURI().toURL());
        for (String s : a[1].split(":")) if (!s.isBlank()) urls.add(new File(s).toURI().toURL());
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), PlayPacketIdScan.class.getClassLoader());
        Class.forName("net.minecraft.SharedConstants", true, cl).getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap", true, cl).getMethod("bootStrap").invoke(null);
        Class<?> gp = Class.forName("net.minecraft.network.protocol.game.GameProtocols", true, cl);
        Class<?> visC = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor", true, cl);
        Class<?> typeC = Class.forName("net.minecraft.network.protocol.PacketType", true, cl);
        for (String[] t : new String[][]{{"CLIENTBOUND_TEMPLATE", "C"}, {"SERVERBOUND_TEMPLATE", "S"}}) {
            Object tmpl = gp.getField(t[0]).get(null);
            Method details = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider", true, cl).getMethod("details");
            details.setAccessible(true);
            Object d = details.invoke(tmpl);
            Method list = Class.forName("net.minecraft.network.ProtocolInfo$Details", true, cl).getMethod("listPackets", visC);
            TreeMap<Integer, String> out = new TreeMap<>();
            Object visitor = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{visC}, (p, m, args) -> { out.put((Integer) args[1], String.valueOf(typeC.getMethod("id").invoke(args[0]))); return null; });
            list.invoke(d, visitor);
            out.forEach((id, n) -> System.out.println(t[1] + " " + id + " " + n));
        }
    }
}
