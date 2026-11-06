package net.fissionmc.master.client

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/** Formats global chat lines on the master (pure functions, unit tested). */
object ChatFormatter {
    /** The visible text of a component tree (text nodes and their children; translatable keys as is). */
    fun plain(c: Component): String {
        val sb = StringBuilder()
        fun walk(x: Component) {
            when (x) { is net.kyori.adventure.text.TextComponent -> sb.append(x.content()); is net.kyori.adventure.text.TranslatableComponent -> sb.append(x.key()); else -> {} }
            x.children().forEach { walk(it) }
        }
        walk(c); return sb.toString()
    }

    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()

    /**
     * Player text made safe: control characters and the section sign (the legacy formatting char) removed, whitespace trimmed, length capped.
     * `&` codes are NOT touched here; whether they act is decided by [messageComponent].
     */
    fun sanitize(text: String, maxLength: Int): String {
        val sb = StringBuilder(text.length)
        for (ch in text) if (ch >= ' ' && ch != '\u007f' && ch != '§') sb.append(ch)
        return sb.toString().trim().let { if (it.length > maxLength) it.substring(0, maxLength) else it }
    }

    /** The player's words as a component: plain text unless [allowColors] (then `&` colour/format codes apply, nothing else can be injected). */
    fun messageComponent(sanitized: String, allowColors: Boolean): Component = if (allowColors) legacy.deserialize(sanitized) else Component.text(sanitized)

    /**
     * The template (`prefix + format + suffix`, `&` codes) with `{name} {displayname} {backend}` replaced by plain text and `{message}` by
     * [message]. The message is substituted last, so placeholders typed by a player are never expanded.
     */
    fun format(s: ChatSettings, name: String, displayName: String, backend: String, message: Component): Component {
        var c: Component = legacy.deserialize(s.prefix + s.format + s.suffix)
        fun rep(token: String, with: Component) { c = c.replaceText(TextReplacementConfig.builder().matchLiteral(token).replacement(with).build()) }
        rep("{name}", Component.text(name)); rep("{displayname}", Component.text(displayName)); rep("{backend}", Component.text(backend))
        rep("{message}", message)
        return c
    }
}
