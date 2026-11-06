package net.fissionmc.protocol

/**
 * Master -> backend (82): the template of LOCAL chat lines (`chat.local.format`) and its syntax (`minimessage` | `legacy`). [local] empty = the backend keeps vanilla's
 * rendering. Sent when the backend joins, on every attach and every 5 s (a first send can precede the backend's handlers). The backend renders local chat itself with
 * the same `ChatTemplate` the master uses for global chat; placeholders {name} {displayname} {uuid} {serverid} {backend} {message}.
 */
data class ChatFormat(val syntax: String, val local: String) : NodeMessage

fun MessageRegistry.registerChatFormatMessages(): MessageRegistry = this
    .register<ChatFormat>(82, { m, b -> b.writeString(m.syntax, 32).writeString(m.local, 8192) }, { b -> ChatFormat(b.readString(32), b.readString(8192)) })
