package net.fissionmc.craft.builtin;

import io.papermc.paper.plugin.provider.source.PluginFlagProviderSource;
import io.papermc.paper.plugin.util.EntrypointUtil;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * FissionMC built-in plugins: tiny generated descriptor jars (written to {@code .fission-builtin/}) fed through the normal
 * plugin provider machinery, so dependency resolution, load order and enabling work exactly as for installed plugins.
 */
public final class BuiltinPlugins {
    private BuiltinPlugins() {}

    /** plugin.yml of the built-in packetevents plugin. */
    /** Name of the generated main class (deliberately not on the server class path). */
    public static final String MAIN = "net.fissionmc.builtin.PacketEventsMain";

    public static final String PACKETEVENTS_YML = "name: packetevents\nversion: 2.0.0-fission\nmain: " + MAIN
        + "\napi-version: '1.13'\nauthor: FissionMC\ndescription: Built-in PacketEvents API (core-fed)\nload: STARTUP\n";

    public static final String FISSION_MAIN = "net.fissionmc.builtin.FissionMain";
    public static final String FISSION_YML = "name: fission\nversion: 0.1.0\nmain: " + FISSION_MAIN
        + "\napi-version: '1.13'\nauthor: FissionMC\ndescription: FissionMC master and relay status\nload: STARTUP\n"
        + "commands:\n  fission:\n    description: FissionMC master and relay status\n    usage: /fission status\n    permission: fission.status\n"
        + "permissions:\n  fission.status:\n    default: op\n";

    public static void register() throws IOException {
        Path dir = Path.of(".fission-builtin");
        Files.createDirectories(dir);
        List<Path> jars = List.of(
            write(dir.resolve("packetevents.jar"), PACKETEVENTS_YML, MAIN, PacketEventsPlugin.class),
            write(dir.resolve("fission.jar"), FISSION_YML, FISSION_MAIN, FissionPlugin.class));
        EntrypointUtil.registerProvidersFromSource(PluginFlagProviderSource.INSTANCE, jars);
    }

    private static Path write(Path jar, String yml, String main, Class<?> target) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry("plugin.yml"));
            j.write(yml.getBytes(StandardCharsets.UTF_8));
            j.closeEntry();
            j.putNextEntry(new JarEntry(main.replace('.', '/') + ".class"));
            j.write(mainClass(main, target.getName().replace('.', '/')));
            j.closeEntry();
        }
        return jar;
    }

    /** {@code class MAIN extends JavaPlugin { onLoad() { target.load(this); } onEnable() { target.enable(this); } }} */
    public static byte[] mainClass(String name, String target) {
        String self = name.replace('.', '/'), sup = "org/bukkit/plugin/java/JavaPlugin";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, self, null, sup, null);
        MethodVisitor c = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode(); c.visitVarInsn(Opcodes.ALOAD, 0); c.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN); c.visitMaxs(0, 0); c.visitEnd();
        for (String m : new String[] {"onLoad", "onEnable"}) {
            MethodVisitor v = cw.visitMethod(Opcodes.ACC_PUBLIC, m, "()V", null, null);
            v.visitCode(); v.visitVarInsn(Opcodes.ALOAD, 0);
            v.visitMethodInsn(Opcodes.INVOKESTATIC, target, m.equals("onLoad") ? "load" : "enable", "(Lorg/bukkit/plugin/java/JavaPlugin;)V", false);
            v.visitInsn(Opcodes.RETURN); v.visitMaxs(0, 0); v.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
