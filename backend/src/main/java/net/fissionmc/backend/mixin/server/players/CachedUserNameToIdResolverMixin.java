package net.fissionmc.backend.mixin.server.players;

import java.util.Locale;
import java.util.Map;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/server/players/CachedUserNameToIdResolver.java.patch */
@Mixin(CachedUserNameToIdResolver.class)
public abstract class CachedUserNameToIdResolverMixin {
    // GameProfileInfo is package-private: typed loosely here, accessed via CachedUserNameToIdResolver_GameProfileInfoMixin.
    @Shadow @Final private Map<String, ?> profilesByName;

    @Shadow private long getNextOperation() { throw new AssertionError(); }

    // TODO(behaviour): Paper adds stateLock/lookupLock (GameProfileCache concurrency) around all map access, blank-name /
    // proxy-online-mode lookup rules, async saving from add/get, corrupted usercache handling and the Spigot userCacheCap.

    // Paper start
    public @org.jspecify.annotations.Nullable NameAndId getIfCached(final String name) {
        Object entry = this.profilesByName.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) {
            return null;
        }
        CachedUserNameToIdResolver_GameProfileInfoMixin info = (CachedUserNameToIdResolver_GameProfileInfoMixin) entry;
        info.fission$setLastAccess(this.getNextOperation());
        return info.fission$nameAndId();
    }
    // Paper end

    // Paper start - Perf: Async GameProfileCache saving
    // TODO(behaviour): Paper moves the save() body here (save() delegates with false) and serialises the entries on the calling
    // thread, writing the file asynchronously; here the whole vanilla save() runs on the async task.
    public void save(final boolean asyncSave) {
        Runnable save = ((CachedUserNameToIdResolver) (Object) this)::save;
        if (asyncSave) {
            io.papermc.paper.util.MCUtil.scheduleAsyncTask(save);
        } else {
            save.run();
        }
    }
    // Paper end - Perf: Async GameProfileCache saving
}
