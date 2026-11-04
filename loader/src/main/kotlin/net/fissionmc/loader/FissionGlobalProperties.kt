package net.fissionmc.loader

import org.spongepowered.asm.service.IGlobalPropertyService
import org.spongepowered.asm.service.IPropertyKey
import java.util.concurrent.ConcurrentHashMap

/** Mixin's cross-classloader blackboard; one JVM-wide map is enough for us. */
class FissionGlobalProperties : IGlobalPropertyService {
    private class Key(val name: String) : IPropertyKey {
        override fun toString() = name
    }

    override fun resolveKey(name: String): IPropertyKey = Key(name)

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(key: IPropertyKey): T? = STORE[key.toString()] as T?

    override fun setProperty(key: IPropertyKey, value: Any?) {
        if (value == null) STORE.remove(key.toString()) else STORE[key.toString()] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(key: IPropertyKey, defaultValue: T): T = (STORE[key.toString()] as T?) ?: defaultValue

    override fun getPropertyString(key: IPropertyKey, defaultValue: String): String =
        STORE[key.toString()]?.toString() ?: defaultValue

    private companion object {
        val STORE = ConcurrentHashMap<String, Any>()
    }
}
