import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.*;
import java.util.zip.*;

/**
 * Finds every vanilla class that builds a ClientboundAddEntityPacket with an explicit int `data` argument (the 3-argument constructor), i.e. the entity types whose
 * `data` field is NOT a plain "0": for projectiles it is the owner's ENTITY ID, which the id rewriter must map; for item frames, paintings, falling blocks ... it is a
 * direction or block state id and must be left alone. Usage (JDK 25, no Gradle): java tools/AddEntityDataScan.java <server jar>
 */
public class AddEntityDataScan {
    public static void main(String[] a) throws Exception {
        try (ZipFile z = new ZipFile(a[0])) {
            for (var e = z.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                if (!ze.getName().endsWith(".class") || !ze.getName().startsWith("net/minecraft/")) continue;
                byte[] bytes = z.getInputStream(ze).readAllBytes();
                ClassModel cm = ClassFile.of().parse(bytes);
                for (MethodModel m : cm.methods()) {
                    m.code().ifPresent(code -> {
                        for (CodeElement el : code) {
                            if (el instanceof InvokeInstruction ii && ii.owner().asInternalName().equals("net/minecraft/network/protocol/game/ClientboundAddEntityPacket") && ii.name().equalsString("<init>")) {
                                String d = ii.type().stringValue();
                                System.out.println(cm.thisClass().asInternalName().replace("net/minecraft/world/entity/", "") + "." + m.methodName().stringValue() + " -> <init>" + d);
                            }
                        }
                    });
                }
            }
        }
    }
}
