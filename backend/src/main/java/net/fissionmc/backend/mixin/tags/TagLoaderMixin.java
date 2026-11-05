package net.fissionmc.backend.mixin.tags;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.tag.PaperTagListenerManager;
import io.papermc.paper.tag.TagEventConfig;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/tags/TagLoader.java.patch (fire tag registrar events / tag lifecycle cause).
 *
 * <p>Paper threads a registrar-event cause and the registry through new overloads down to
 * {@code build(Map, TagEventConfig)}. Here the vanilla overloads stay the entry points: the Paper overloads with a
 * cause put it (and the registry) into thread-locals and call the vanilla method, and the {@code build(Map)} calls in
 * {@code loadTagsForRegistry}/{@code loadPendingTags} are wrapped with Paper's pre/post flatten events.
 */
@Mixin(TagLoader.class)
public abstract class TagLoaderMixin<T> {
    @Unique private static final ThreadLocal<ReloadableRegistrarEvent.Cause> fission$cause = new ThreadLocal<>();
    @Unique private static final ThreadLocal<WritableRegistry<?>> fission$registry = new ThreadLocal<>();

    @Shadow public static List<Registry.PendingTags<?>> loadTagsForExistingRegistries(final ResourceManager manager, final RegistryAccess layer) { throw new AssertionError(); }
    @Shadow public static <T> void loadTagsForRegistry(final ResourceManager manager, final WritableRegistry<T> registry) { throw new AssertionError(); }
    @Shadow public static <T> Map<TagKey<T>, List<Holder<T>>> loadTagsForRegistry(final ResourceManager manager, final ResourceKey<? extends Registry<T>> registryKey, final TagLoader.ElementLookup<Holder<T>> lookup) { throw new AssertionError(); }

    @Shadow public abstract Map<Identifier, List<T>> build(final Map<Identifier, List<TagLoader.EntryWithSource>> builders);

    @Unique
    private static ReloadableRegistrarEvent.Cause fission$causeOrInitial() {
        final ReloadableRegistrarEvent.Cause cause = fission$cause.get();
        return cause == null ? ReloadableRegistrarEvent.Cause.INITIAL : cause;
    }

    // Paper start - fire tag registrar events
    public Map<Identifier, List<T>> build(Map<Identifier, List<TagLoader.EntryWithSource>> builders, io.papermc.paper.tag.@Nullable TagEventConfig<T, ?> eventConfig) {
        builders = PaperTagListenerManager.INSTANCE.firePreFlattenEvent(builders, eventConfig);
        return PaperTagListenerManager.INSTANCE.firePostFlattenEvent(this.build(builders), eventConfig); // Paper - fire tag registrar events
    }
    // Paper end

    // Paper start - tag lifecycle - add cause
    // Surface: public static. Mixin requires non-public statics; the loader widens it.
    private static List<Registry.PendingTags<?>> loadTagsForExistingRegistries(
        final ResourceManager manager, final RegistryAccess layer, final ReloadableRegistrarEvent.Cause cause
    ) {
        final ReloadableRegistrarEvent.Cause previous = fission$cause.get();
        fission$cause.set(cause);
        try {
            return loadTagsForExistingRegistries(manager, layer);
        } finally {
            fission$cause.set(previous);
        }
    }
    // Paper end - tag lifecycle - add cause

    // Paper start - tag lifecycle - add registrar event cause
    // Surface: public static.
    private static <T> void loadTagsForRegistry(final ResourceManager manager, final WritableRegistry<T> registry, ReloadableRegistrarEvent.Cause cause) {
        loadTagsForRegistry(manager, registry.key(), TagLoader.ElementLookup.fromWritableRegistry(registry), registry, cause);
    }

    // Surface: public static.
    private static <T> Map<TagKey<T>, List<Holder<T>>> loadTagsForRegistry(
        final ResourceManager manager, final ResourceKey<? extends Registry<T>> registryKey, final TagLoader.ElementLookup<Holder<T>> lookup, final WritableRegistry<T> registry, final ReloadableRegistrarEvent.Cause cause
    ) {
        final ReloadableRegistrarEvent.Cause previousCause = fission$cause.get();
        final WritableRegistry<?> previousRegistry = fission$registry.get();
        fission$cause.set(cause);
        fission$registry.set(registry);
        try {
            return loadTagsForRegistry(manager, registryKey, lookup);
        } finally {
            fission$cause.set(previousCause);
            fission$registry.set(previousRegistry);
        }
    }
    // Paper end - tag lifecycle - add registrar event cause

    // vanilla loadTagsForRegistry(manager, WritableRegistry) -> Paper: loadTagsForRegistry(manager, registry, Cause.INITIAL)
    @WrapOperation(method = "loadTagsForRegistry(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/WritableRegistry;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/tags/TagLoader;loadTagsForRegistry(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/tags/TagLoader$ElementLookup;)Ljava/util/Map;"))
    private static Map<?, ?> fission$writableRegistryCause(ResourceManager manager, ResourceKey<?> registryKey, TagLoader.ElementLookup<?> lookup, Operation<Map<?, ?>> original,
                                                          ResourceManager manager0, WritableRegistry<?> registry) {
        final WritableRegistry<?> previousRegistry = fission$registry.get();
        fission$registry.set(registry);
        try {
            return original.call(manager, registryKey, lookup);
        } finally {
            fission$registry.set(previousRegistry);
        }
    }

    // loadTagsForRegistry(manager, key, lookup[, registry, cause]): loader.build(loader.load(manager), createEventConfig(registry, cause))
    @WrapOperation(method = "loadTagsForRegistry(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/tags/TagLoader$ElementLookup;)Ljava/util/Map;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/tags/TagLoader;build(Ljava/util/Map;)Ljava/util/Map;"))
    private static Map<Identifier, List<Object>> fission$registryTagEvents(TagLoader<Object> loader, Map<Identifier, List<TagLoader.EntryWithSource>> builders, Operation<Map<Identifier, List<Object>>> original) {
        final WritableRegistry<?> registry = fission$registry.get();
        // Paper passes a null registry from the 3-argument overload (unused there); no events in that case.
        if (registry == null) return original.call(loader, builders);
        return fission$buildWithEvents(loader, builders, (TagEventConfig) PaperTagListenerManager.INSTANCE.createEventConfig(registry, fission$causeOrInitial()), original); // Paper - tag lifecycle
    }

    // loadPendingTags(manager, registry[, cause]): loader.build(loader.load(manager), createEventConfig(registry, cause))
    @WrapOperation(method = "loadPendingTags", at = @At(value = "INVOKE", target = "Lnet/minecraft/tags/TagLoader;build(Ljava/util/Map;)Ljava/util/Map;"))
    private static Map<Identifier, List<Object>> fission$pendingTagEvents(TagLoader<Object> loader, Map<Identifier, List<TagLoader.EntryWithSource>> builders, Operation<Map<Identifier, List<Object>>> original,
                                                                         ResourceManager manager, Registry<?> registry) {
        return fission$buildWithEvents(loader, builders, (TagEventConfig) PaperTagListenerManager.INSTANCE.createEventConfig(registry, fission$causeOrInitial()), original); // Paper - add registrar event cause
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Identifier, List<Object>> fission$buildWithEvents(TagLoader<Object> loader, Map<Identifier, List<TagLoader.EntryWithSource>> builders, @Nullable TagEventConfig config, Operation<Map<Identifier, List<Object>>> original) {
        // Paper start - fire tag registrar events
        builders = PaperTagListenerManager.INSTANCE.firePreFlattenEvent(builders, config);
        return PaperTagListenerManager.INSTANCE.firePostFlattenEvent(original.call(loader, builders), config);
        // Paper end - fire tag registrar events
    }
}
