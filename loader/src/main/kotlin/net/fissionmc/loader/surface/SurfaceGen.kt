package net.fissionmc.loader.surface

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the compile-only surface jar: every class of the given jars that the surface touches,
 * access-widened and with Paper's added members declared (bodies throw). Put it first on the
 * compile classpath, before the vanilla jar and libraries.
 *
 * Args: <surface dir> <out jar> <in jar>...
 */
object SurfaceGen {
    @JvmStatic
    fun main(args: Array<String>) {
        val surface = Surface.load(File(args[0]))
        val out = File(args[1])
        out.parentFile.mkdirs()
        val seen = HashSet<String>()
        var count = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (path in args.drop(2)) {
                JarFile(path).use { jar ->
                    for (e in jar.entries()) {
                        if (!e.name.endsWith(".class")) continue
                        val internal = e.name.removeSuffix(".class")
                        if (!seen.add(e.name)) continue
                        val node = ClassNode()
                        ClassReader(jar.getInputStream(e).readBytes()).accept(node, 0)
                        if (!surface.touches(internal) && !surface.referencesTouched(node)) continue
                        surface.addMembers(node)
                        val w = ClassWriter(ClassWriter.COMPUTE_MAXS)
                        node.accept(w)
                        zip.putNextEntry(ZipEntry(e.name))
                        zip.write(w.toByteArray())
                        zip.closeEntry()
                        count++
                    }
                }
            }
        }
        println("surface: $count classes -> $out")
    }
}
