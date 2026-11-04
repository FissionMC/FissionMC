package net.fissionmc.backend.mixin.network.chat;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/network/chat/Component.java.patch (Component implements Iterable<Component>) */
@Mixin(Component.class)
public interface ComponentMixin extends Iterable<Component> { // CraftBukkit

    // CraftBukkit start
    default java.util.stream.Stream<Component> stream() {
        return com.google.common.collect.Streams.concat(java.util.stream.Stream.of((Component) (Object) this), ((Component) (Object) this).getSiblings().stream().flatMap(Component::stream));
    }

    @Override
    default java.util.Iterator<Component> iterator() {
        return this.stream().iterator();
    }
    // CraftBukkit end
}
