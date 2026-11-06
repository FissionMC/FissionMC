package net.fissionmc.master.client

import net.kyori.adventure.text.Component

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
    fun messageComponent(sanitized: String, allowColors: Boolean): Component = net.fissionmc.master.chat.ChatTemplate.messageComponent(sanitized, allowColors)

    /**
     * The global template (`prefix + format + suffix`, syntax per [ChatSettings.syntax]) rendered by [net.fissionmc.master.chat.ChatTemplate]: `{name} {displayname} {uuid}
     * {serverid} {backend}` as plain components, `{message}` last as the given component, so player text never injects tags, codes or placeholders.
     */
    fun format(s: ChatSettings, name: String, displayName: String, backend: String, message: Component, uuid: java.util.UUID = java.util.UUID(0, 0)): Component =
        net.fissionmc.master.chat.ChatTemplate.render(s.prefix + s.globalTemplate() + s.suffix, s.syntax, name, Component.text(displayName), uuid, backend, message)
}
