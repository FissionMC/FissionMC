package net.fissionmc.loader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual
import org.spongepowered.asm.launch.platform.container.IContainerHandle
import org.spongepowered.asm.logging.ILogger
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import org.spongepowered.asm.service.*
import java.io.InputStream
import java.net.URL

/** Mixin service binding Mixin to [FissionClassLoader] (the role KnotMixin plays for Fabric). */
class FissionMixinService : MixinServiceAbstract(), IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {

    private val container = ContainerHandleVirtual(name)
    private var factory: IMixinTransformerFactory? = null
    private val invalid = HashSet<String>()

    override fun getName() = "FissionMC"
    override fun isValid() = game != null
    override fun getInitialPhase(): MixinEnvironment.Phase = MixinEnvironment.Phase.PREINIT
    override fun getMaxCompatibilityLevel(): MixinEnvironment.CompatibilityLevel = MixinEnvironment.CompatibilityLevel.JAVA_25

    override fun offer(internal: IMixinInternal) {
        if (internal is IMixinTransformerFactory) factory = internal
        else super.offer(internal)
    }

    /** Creates the transformer and installs it in the game loader. */
    fun installTransformer(): IMixinTransformer {
        val t = factory?.createTransformer() ?: error("Mixin did not offer a transformer factory")
        game!!.transformer = t
        return t
    }

    override fun getClassProvider(): IClassProvider = this
    override fun getBytecodeProvider(): IClassBytecodeProvider = this
    override fun getTransformerProvider(): ITransformerProvider = this
    override fun getClassTracker(): IClassTracker = this
    override fun getAuditTrail(): IMixinAuditTrail? = null
    override fun getFeatureValidator(): IFeatureValidator = IFeatureValidator.ALLOW_ALL
    override fun getAdviceProvider(): IAdviceProvider = IAdviceProvider.GENERIC
    override fun getPlatformAgents(): Collection<String> = emptyList()
    override fun getPrimaryContainer(): IContainerHandle = container
    override fun getMixinContainers(): Collection<IContainerHandle> = emptyList()
    override fun getResourceAsStream(name: String): InputStream? = game!!.getResourceAsStream(name)
    override fun createLogger(name: String): ILogger = FissionLogger(name)

    // IClassProvider
    @Deprecated("Deprecated in Java")
    override fun getClassPath(): Array<URL> = game!!.urLs
    override fun findClass(name: String): Class<*> = Class.forName(name, true, game)
    override fun findClass(name: String, initialize: Boolean): Class<*> = Class.forName(name, initialize, game)
    override fun findAgentClass(name: String, initialize: Boolean): Class<*> = Class.forName(name, initialize, game)

    // IClassBytecodeProvider
    override fun getClassNode(name: String): ClassNode = getClassNode(name, true)
    override fun getClassNode(name: String, runTransformers: Boolean): ClassNode = getClassNode(name, runTransformers, 0)
    override fun getClassNode(name: String, runTransformers: Boolean, readerFlags: Int): ClassNode {
        val bytes = game!!.rawBytes(name) ?: throw ClassNotFoundException(name)
        val node = ClassNode()
        ClassReader(bytes).accept(node, readerFlags)
        return node
    }

    // ITransformerProvider
    override fun getTransformers(): Collection<ITransformer> = emptyList()
    override fun getDelegatedTransformers(): Collection<ITransformer> = emptyList()
    override fun addTransformerExclusion(name: String) {}

    // IClassTracker
    override fun registerInvalidClass(name: String) { invalid += name }
    override fun isClassLoaded(name: String) = game!!.isLoaded(name)
    override fun getClassRestrictions(name: String): String = ""

    companion object {
        @Volatile
        var game: FissionClassLoader? = null
    }
}
