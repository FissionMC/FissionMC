import java.io.File;
import java.nio.file.Files;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates net/fissionmc/testpluginlegacy/LegacyShapes.class: bytecode shaped like a plugin compiled against an OLD Bukkit API
 * (pre-flattening Material names, enum-typed registries, renamed classes, getTypeId, ...). Class file version 50 (no stack map frames).
 * Each check*() returns an Object; LegacyMain compares its String form with the expectation of the plugin variant.
 */
public final class LegacyShapesGen implements Opcodes {
    static final String MAT = "org/bukkit/Material", ITEM = "org/bukkit/inventory/ItemStack", BIOME = "org/bukkit/block/Biome", STR = "java/lang/String", OBJ = "java/lang/Object";
    static ClassWriter cw;

    interface Body { void emit(MethodVisitor m); }

    static void check(String name, Body b) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "check" + name, "()Ljava/lang/Object;", null, null);
        m.visitCode();
        b.emit(m);
        m.visitInsn(ARETURN);
        m.visitMaxs(8, 4);
        m.visitEnd();
    }

    static void nameOf(MethodVisitor m, String owner) { m.visitMethodInsn(INVOKEVIRTUAL, owner, "name", "()Ljava/lang/String;", false); }

    public static void main(String[] args) throws Exception {
        cw = new ClassWriter(0);
        cw.visit(V1_6, ACC_PUBLIC | ACC_SUPER, "net/fissionmc/testpluginlegacy/LegacyShapes", null, OBJ, null);

        check("MysqlDriver", m -> m.visitLdcInsn("com.mysql.jdbc.Driver"));
        check("RenamedClassMount", m -> { m.visitLdcInsn(Type.getObjectType("org/spigotmc/event/entity/EntityMountEvent")); m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false); });
        check("RenamedClassCow", m -> { m.visitLdcInsn(Type.getObjectType("org/bukkit/entity/Cow")); m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false); });

        check("MaterialFieldWool", m -> { m.visitFieldInsn(GETSTATIC, MAT, "WOOL", "L" + MAT + ";"); nameOf(m, MAT); });
        check("MaterialGetMaterial", m -> { m.visitLdcInsn("WOOL"); m.visitMethodInsn(INVOKESTATIC, MAT, "getMaterial", "(Ljava/lang/String;)L" + MAT + ";", false); nameOf(m, MAT); });
        check("MaterialValueOf", m -> { m.visitLdcInsn("WOOD_DOOR"); m.visitMethodInsn(INVOKESTATIC, MAT, "valueOf", "(Ljava/lang/String;)L" + MAT + ";", false); nameOf(m, MAT); });
        check("MaterialOrdinal", m -> {
            Label bad = new Label(), end = new Label();
            m.visitFieldInsn(GETSTATIC, MAT, "WOOL", "L" + MAT + ";");
            m.visitMethodInsn(INVOKEVIRTUAL, MAT, "ordinal", "()I", false);
            m.visitJumpInsn(IFLT, bad);
            m.visitLdcInsn("ok");
            m.visitJumpInsn(GOTO, end);
            m.visitLabel(bad);
            m.visitLdcInsn("bad");
            m.visitLabel(end);
        });
        check("MaterialValues", m -> {
            m.visitMethodInsn(INVOKESTATIC, MAT, "values", "()[L" + MAT + ";", false);
            m.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
            m.visitFieldInsn(GETSTATIC, MAT, "WOOL", "L" + MAT + ";");
            m.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "contains", "(Ljava/lang/Object;)Z", true);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        });
        check("ItemStackShort", m -> {
            m.visitTypeInsn(NEW, ITEM); m.visitInsn(DUP);
            m.visitFieldInsn(GETSTATIC, MAT, "WOOL", "L" + MAT + ";");
            m.visitInsn(ICONST_1); m.visitIntInsn(BIPUSH, 14);
            m.visitMethodInsn(INVOKESPECIAL, ITEM, "<init>", "(L" + MAT + ";IS)V", false);
            m.visitMethodInsn(INVOKEVIRTUAL, OBJ, "toString", "()Ljava/lang/String;", false);
        });
        check("BlockGetTypeId", m -> {
            Label bad = new Label(), end = new Label();
            m.visitMethodInsn(INVOKESTATIC, "org/bukkit/Bukkit", "getWorlds", "()Ljava/util/List;", false);
            m.visitInsn(ICONST_0);
            m.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
            m.visitTypeInsn(CHECKCAST, "org/bukkit/World");
            m.visitInsn(ICONST_0); m.visitIntInsn(BIPUSH, 64); m.visitInsn(ICONST_0);
            m.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/World", "getBlockAt", "(III)Lorg/bukkit/block/Block;", true);
            m.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/block/Block", "getTypeId", "()I", true);
            m.visitJumpInsn(IFLT, bad);
            m.visitLdcInsn("ok");
            m.visitJumpInsn(GOTO, end);
            m.visitLabel(bad);
            m.visitLdcInsn("bad");
            m.visitLabel(end);
        });
        check("DyeColorSilver", m -> { m.visitFieldInsn(GETSTATIC, "org/bukkit/DyeColor", "SILVER", "Lorg/bukkit/DyeColor;"); nameOf(m, "org/bukkit/DyeColor"); });
        check("MaterialSignRename", m -> { m.visitFieldInsn(GETSTATIC, MAT, "SIGN", "L" + MAT + ";"); nameOf(m, MAT); });
        check("MaterialGrassRename", m -> { m.visitFieldInsn(GETSTATIC, MAT, "GRASS", "L" + MAT + ";"); nameOf(m, MAT); });

        check("BiomeName", m -> { m.visitFieldInsn(GETSTATIC, BIOME, "PLAINS", "L" + BIOME + ";"); nameOf(m, BIOME); });
        check("BiomeValueOf", m -> { m.visitLdcInsn("PLAINS"); m.visitMethodInsn(INVOKESTATIC, BIOME, "valueOf", "(Ljava/lang/String;)L" + BIOME + ";", false); nameOf(m, BIOME); });
        check("EnumValueOfBiome", m -> {
            m.visitLdcInsn(Type.getObjectType(BIOME)); m.visitLdcInsn("PLAINS");
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
            nameOf(m, "java/lang/Enum");
        });
        check("EnumMapBiome", m -> {
            m.visitTypeInsn(NEW, "java/util/EnumMap"); m.visitInsn(DUP);
            m.visitLdcInsn(Type.getObjectType(BIOME));
            m.visitMethodInsn(INVOKESPECIAL, "java/util/EnumMap", "<init>", "(Ljava/lang/Class;)V", false);
            m.visitMethodInsn(INVOKEVIRTUAL, OBJ, "getClass", "()Ljava/lang/Class;", false);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false);
        });
        check("SoundValueOf", m -> { m.visitLdcInsn("ENTITY_EXPERIENCE_ORB_PICKUP"); m.visitMethodInsn(INVOKESTATIC, "org/bukkit/Sound", "valueOf", "(Ljava/lang/String;)Lorg/bukkit/Sound;", false); nameOf(m, "org/bukkit/Sound"); });

        cw.visitEnd();
        File out = new File(args[0], "net/fissionmc/testpluginlegacy/LegacyShapes.class");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), cw.toByteArray());
    }
}
