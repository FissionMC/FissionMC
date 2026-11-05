package net.fissionmc.backend.support;

import java.util.Set;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import org.jspecify.annotations.Nullable;

/** Records which components {@code applyImplicitComponents} read (CraftBukkit). Top-level, not anonymous, so a mixin can use it (see NoopLevelLoadListener). */
public final class ImplicitComponentGetter implements DataComponentGetter {
    private final Set<DataComponentType<?>> implicitComponents;
    private final DataComponentMap fullView;

    public ImplicitComponentGetter(final Set<DataComponentType<?>> implicitComponents, final DataComponentMap fullView) {
        this.implicitComponents = implicitComponents;
        this.fullView = fullView;
    }

    @Override
    public <T> @Nullable T get(final DataComponentType<? extends T> type) {
        this.implicitComponents.add(type);
        return this.fullView.get(type);
    }

    @Override
    public <T> T getOrDefault(final DataComponentType<? extends T> type, final T defaultValue) {
        this.implicitComponents.add(type);
        return this.fullView.getOrDefault(type, defaultValue);
    }
}
