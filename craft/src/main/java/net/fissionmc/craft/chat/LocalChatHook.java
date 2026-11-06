package net.fissionmc.craft.chat;

import io.papermc.paper.chat.ChatRenderer;

/**
 * FissionMC: the renderer of LOCAL chat lines pushed by the master (`chat.local.format`), set by the backend's LocalChatBackend. {@code ChatProcessor.complete()} uses it
 * (as a system message to every viewer) when the event still has Paper's default renderer, so plugins that set their own renderer or modify the message keep control. Null = Paper's default.
 */
public final class LocalChatHook {
    public static volatile ChatRenderer renderer;

    private LocalChatHook() {}
}
