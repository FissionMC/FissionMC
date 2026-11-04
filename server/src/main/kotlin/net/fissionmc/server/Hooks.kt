package net.fissionmc.server

/** Kotlin side of the thin Java mixin shims. All real logic lives here. */
object Hooks {
    @JvmStatic
    fun onMain() {
        System.err.println("[FissionMC] loader active, vanilla main reached through Mixin")
    }
}
