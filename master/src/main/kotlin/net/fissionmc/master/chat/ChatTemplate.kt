package net.fissionmc.master.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ObjectComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.Context
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.`object`.ObjectContents
import net.kyori.adventure.text.`object`.PlayerHeadObjectContents
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.UUID

/**
 * Chat line templates, ONE implementation for both sides: the master renders global chat with it, the backends render local chat with it (the backend calls this class
 * from the :master jar on its own adventure, so the placeholder semantics cannot drift; `scripts` + `ChatTemplateTest` + `gradlew :backend:chatVectors` run the same
 * vectors, `master/src/main/resources/chat-vectors.txt`, on both adventure lines). Syntax is `minimessage` (default) or `legacy`:
 *
 * - **minimessage**: MiniMessage tags; legacy `&` codes in the same template are converted first (`&r` = `<reset>`, `&#rrggbb` = `<#rrggbb>`, `&0`-`&f` = the colour tag,
 *   `&k &l &m &n &o` = decoration tags; `&` + anything else stays text). A literal `<` is written `\<`. `<head:UUID-or-name[:hat]>` is the player head object component.
 * - **legacy**: only `&` codes and `&#rrggbb` (the old format).
 *
 * Placeholders `{name} {displayname} {uuid} {serverid} {backend} ({backend} = {serverid}) {message}` are substituted AFTER parsing, as components, so player text (name, display
 * name, message) can never inject tags or codes. The one exception is `{uuid}`, which is substituted as text before parsing so it can be a tag argument (`<head:{uuid}>`): it
 * is always 36 characters of hex and dashes.
 */
object ChatTemplate {
    const val MINIMESSAGE = "minimessage"
    const val LEGACY = "legacy"

    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()

    private val colorTags = mapOf(
        '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua", '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray",
        '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua", 'c' to "red", 'd' to "light_purple", 'e' to "yellow", 'f' to "white",
    )
    private val decoTags = mapOf('k' to "obfuscated", 'l' to "bold", 'm' to "strikethrough", 'n' to "underlined", 'o' to "italic")

    /** `&` codes to MiniMessage tags (see the class comment). */
    fun legacyToMini(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '&' && i + 1 < s.length) {
                val n = s[i + 1].lowercaseChar()
                if (n == '#' && i + 8 <= s.length && s.substring(i + 2, i + 8).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { sb.append("<#").append(s, i + 2, i + 8).append('>'); i += 8; continue }
                if (n == 'r') { sb.append("<reset>"); i += 2; continue }
                val color = colorTags[n]
                if (color != null) { sb.append('<').append(color).append('>'); i += 2; continue }
                val deco = decoTags[n]
                if (deco != null) { sb.append('<').append(deco).append('>'); i += 2; continue }
            }
            sb.append(ch); i++
        }
        return sb.toString()
    }

    private val head: TagResolver = TagResolver.resolver("head") { args: ArgumentQueue, _: Context ->
        val who = args.popOr("<head> needs a player uuid or name").value()
        val hat = if (args.hasNext()) args.pop().value().lowercase() != "false" else true
        val contents = ObjectContents.playerHead().hat(hat).also { b -> runCatching { UUID.fromString(who) }.getOrNull()?.let { b.id(it) } ?: b.name(who) }.build()
        Tag.inserting(Component.`object`(contents))
    }

    private val mini: MiniMessage = MiniMessage.builder().tags(TagResolver.resolver(StandardTags.defaults(), head)).build()

    /** The placeholder tokens and the (internal) tag names they become. */
    private val tokens = listOf("{name}" to "fx-name", "{displayname}" to "fx-displayname", "{serverid}" to "fx-serverid", "{backend}" to "fx-serverid", "{message}" to "fx-message")

    /**
     * Renders [template]. [name] is the player's account name, [displayName] their display name component, [serverId] the backend node id (the backend that
     * the player is on), [message] the player's words as a component (built by the caller: plain or with `&` codes, never parsed here).
     */
    fun render(template: String, syntax: String, name: String, displayName: Component, uuid: UUID, serverId: String, message: Component): Component {
        val withUuid = template.replace("{uuid}", uuid.toString())
        if (syntax.equals(LEGACY, ignoreCase = true)) {
            var c: Component = legacy.deserialize(withUuid)
            fun rep(token: String, with: Component) { c = c.replaceText(TextReplacementConfig.builder().matchLiteral(token).replacement(with).build()) }
            rep("{name}", Component.text(name)); rep("{displayname}", displayName); rep("{serverid}", Component.text(serverId)); rep("{backend}", Component.text(serverId))
            rep("{message}", message)
            return c
        }
        var s = legacyToMini(withUuid)
        for ((tok, tag) in tokens) s = s.replace(tok, "<$tag>")
        return mini.deserialize(s, TagResolver.resolver(
            Placeholder.component("fx-name", Component.text(name)), Placeholder.component("fx-displayname", displayName),
            Placeholder.component("fx-serverid", Component.text(serverId)), Placeholder.component("fx-message", message),
        ))
    }

    /** Player text as a component: plain unless [allowColors] (then `&` codes, nothing else, apply). */
    fun messageComponent(sanitized: String, allowColors: Boolean): Component = if (allowColors) legacy.deserialize(sanitized) else Component.text(sanitized)

    /** A flat, version independent description of a rendered component, for the shared test vectors: `text{#rrggbb,bold+italic}` runs joined by `|`, heads as `head(uuid-or-name,hat)`. */
    fun describe(c: Component): String {
        val out = ArrayList<String>()
        fun walk(x: Component, color: String?, decos: Set<TextDecoration>) {
            var col = color; val d = decos.toMutableSet()
            x.color()?.let { col = it.asHexString() }
            for (dec in TextDecoration.values()) when (x.decoration(dec)) { TextDecoration.State.TRUE -> d += dec; TextDecoration.State.FALSE -> d -= dec; else -> {} }
            val style = "{${col ?: "-"}${if (d.isEmpty()) "" else "," + d.map { it.name.lowercase() }.sorted().joinToString("+")}}"
            when (x) {
                is TextComponent -> if (x.content().isNotEmpty()) out += x.content() + style
                is ObjectComponent -> { val o = x.contents(); out += "head(" + (if (o is PlayerHeadObjectContents) (o.id()?.toString() ?: o.name() ?: "?") + "," + o.hat() else o.toString()) + ")" + style }
                else -> out += x.javaClass.simpleName + style
            }
            x.children().forEach { walk(it, col, d) }
        }
        walk(c, null, emptySet())
        return out.joinToString("|")
    }
}
