package net.fissionmc.backend.network;

import java.util.function.Supplier;
import org.bukkit.craftbukkit.util.Waitable;

/** FissionMC: named replacement for Paper's anonymous Waitable subclasses (Mixin cannot load anonymous classes declared in a mixin). */
public final class SupplierWaitable<T> extends Waitable<T> {
    private final Supplier<T> supplier;

    public SupplierWaitable(final Supplier<T> supplier) {
        this.supplier = supplier;
    }

    @Override
    protected T evaluate() {
        return this.supplier.get();
    }
}
