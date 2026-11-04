package net.fissionmc.backend.mixin.server.players;

import net.minecraft.server.players.StoredUserList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accessor for the protected StoredUserList#contains (PlayerList calls it from the same package in Paper; used by PlayerListMixin). */
@Mixin(StoredUserList.class)
public interface StoredUserListMixin {
    @Invoker("contains")
    boolean fission$contains(Object user);
}
