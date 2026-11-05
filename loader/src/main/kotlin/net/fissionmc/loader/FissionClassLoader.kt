package net.fissionmc.loader

import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import java.net.URL
import java.net.URLClassLoader
import java.security.CodeSource

/**
 * Game class loader: child-first for everything except the JDK and the loader's own libraries,
 * so vanilla, the bundled libraries and the FissionMC modules are all defined here and pass
 * through Mixin on the way in.
 */
class FissionClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader("fission", urls, parent) {

    @Volatile
    var transformer: IMixinTransformer? = null

    /** Access widening for Paper's AT and mixin-added members; runs after Mixin. */
    @Volatile
    var surface: net.fissionmc.loader.surface.Surface? = null

    init {
        registerAsParallelCapable()
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val c = if (parentFirst(name)) super.loadClass(name, false) else try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                parent.loadClass(name)
            }
            if (resolve) resolveClass(c)
            return c
        }
    }

    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val url = findResource(path) ?: return defineGenerated(name)
        var bytes = url.openStream().use { it.readBytes() }
        transformer?.let { bytes = it.transformClassBytes(name, name, bytes) }
        surface?.let { s -> if (s.touches(name.replace('.', '/'))) s.applyAccess(bytes)?.let { bytes = it } }
        return defineClass(name, bytes, 0, bytes.size, CodeSource(url, null as Array<java.security.cert.Certificate>?))
    }

    /**
     * Classes Mixin synthesizes at runtime have no .class file: the inner/anonymous classes of a mixin (javac's `$SwitchMap$` helpers for enum
     * switches, anonymous classes) are re-parented to their target as `Target$Anonymous$hash`, and Mixin hands out their bytes through
     * `IMixinTransformer.generateClass` (this is what every other Mixin loader does in findClass). Without it the first use fails with
     * NoClassDefFoundError `...$Anonymous$...` (seen for PrepareSpawnTask and PlayerList).
     */
    private fun defineGenerated(name: String): Class<*> {
        val bytes = transformer?.generateClass(org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment(), name) ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    /** Original, untransformed bytes — what Mixin asks for when it inspects a class. */
    fun rawBytes(name: String): ByteArray? =
        findResource(name.replace('.', '/') + ".class")?.openStream()?.use { it.readBytes() }
            ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }

    fun isLoaded(name: String): Boolean = findLoadedClass(name) != null

    private fun parentFirst(name: String) = PARENT_FIRST.any { name.startsWith(it) }

    companion object {
        private val PARENT_FIRST = listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.spongepowered.", "org.objectweb.asm.", "net.fissionmc.loader.", "com.llamalad7.mixinextras.",
        )
    }
}
