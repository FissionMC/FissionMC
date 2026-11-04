package net.fissionmc.loader

import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.service.MixinService
import java.io.File

/**
 * Entry point. Builds the game class loader from `-Dfission.classpath`, wires Mixin to it, then
 * runs vanilla's `net.minecraft.server.Main` through it.
 *
 * Properties: `fission.classpath` (path-separated game classpath), `fission.mixins`
 * (comma-separated mixin configs), `fission.main` (defaults to vanilla's server main).
 */
object Bootstrap {
    @JvmStatic
    fun main(args: Array<String>) {
        val cp = System.getProperty("fission.classpath") ?: error("-Dfission.classpath is required")
        val urls = cp.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it).toURI().toURL() }
        val mainClass = System.getProperty("fission.main", "net.minecraft.server.Main")

        val loader = FissionClassLoader(urls.toTypedArray(), Bootstrap::class.java.classLoader)
        FissionMixinService.game = loader
        System.setProperty("mixin.service", FissionMixinService::class.java.name)

        MixinBootstrap.init()
        System.getProperty("fission.mixins", "").split(',').filter { it.isNotBlank() }.forEach(Mixins::addConfiguration)
        MixinEnvironment.getDefaultEnvironment().side = MixinEnvironment.Side.SERVER
        // Mixin only exposes phase changes package-privately; launchers drive them reflectively.
        val goto = MixinEnvironment::class.java.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase::class.java)
        goto.isAccessible = true
        goto.invoke(null, MixinEnvironment.Phase.INIT)
        goto.invoke(null, MixinEnvironment.Phase.DEFAULT)
        (MixinService.getService() as FissionMixinService).installTransformer()

        Thread.currentThread().contextClassLoader = loader
        loader.loadClass(mainClass).getMethod("main", Array<String>::class.java).invoke(null, args)
    }
}
