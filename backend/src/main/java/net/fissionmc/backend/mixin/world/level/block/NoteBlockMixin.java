package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.redstone.Orientation;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/NoteBlock.java.patch */
@SuppressWarnings("unused")
@Mixin(NoteBlock.class)
public abstract class NoteBlockMixin {
    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
    private void fission$redstone(final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston, final CallbackInfo ci) {
        // Paper start - Call BlockRedstoneEvent (FissionMC: signal recomputed at HEAD, vanilla reads are side-effect free)
        boolean signal = level.hasNeighborSignal(pos);
        if (signal != state.getValue(NoteBlock.POWERED)) {
            if (!CraftEventFactory.callBinaryRedstoneChange(level, pos, signal)) {
                ci.cancel();
            }
        }
        // Paper end - Call BlockRedstoneEvent
    }

    @WrapOperation(method = "neighborChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean fission$refreshState(final Level level, final BlockPos pos, final BlockState newState, final Operation<Boolean> original) {
        if (newState.getValue(NoteBlock.POWERED)) {
            return original.call(level, pos, level.getBlockState(pos).setValue(NoteBlock.POWERED, true)); // CraftBukkit - SPIGOT-5617: update in case changed in event
        }
        return original.call(level, pos, newState);
    }

    // Paper start - move NotePlayEvent call to fix instrument/note changes
    @Inject(method = "triggerEvent", at = @At("HEAD"), cancellable = true)
    private void fission$notePlay(final BlockState state, final Level level, final BlockPos pos, final int b0, final int b1, final CallbackInfoReturnable<Boolean> cir,
                                   @com.llamalad7.mixinextras.sugar.Share("event") final com.llamalad7.mixinextras.sugar.ref.LocalRef<org.bukkit.event.block.NotePlayEvent> eventRef) {
        org.bukkit.event.block.NotePlayEvent event = CraftEventFactory.callNotePlayEvent(level, pos, state.getValue(NoteBlock.INSTRUMENT), state.getValue(NoteBlock.NOTE));
        if (event.isCancelled()) cir.setReturnValue(false);
        eventRef.set(event);
    }

    @ModifyExpressionValue(method = "triggerEvent", at = @At(value = "INVOKE", ordinal = 0, target = "Ljava/lang/Integer;intValue()I"))
    private int fission$eventNote(final int note, @com.llamalad7.mixinextras.sugar.Share("event") final com.llamalad7.mixinextras.sugar.ref.LocalRef<org.bukkit.event.block.NotePlayEvent> eventRef) {
        return eventRef.get().getNote().getId(); // Paper - move NotePlayEvent call to fix instrument/note changes
    }

    @WrapOperation(method = "triggerEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/properties/NoteBlockInstrument;getSoundEvent()Lnet/minecraft/core/Holder;"))
    private net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> fission$eventInstrument(final net.minecraft.world.level.block.state.properties.NoteBlockInstrument instrument, final Operation<net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>> original,
                                                                                                  @com.llamalad7.mixinextras.sugar.Share("event") final com.llamalad7.mixinextras.sugar.ref.LocalRef<org.bukkit.event.block.NotePlayEvent> eventRef) {
        return org.bukkit.craftbukkit.block.data.CraftBlockData.toVanilla(eventRef.get().getInstrument(), net.minecraft.world.level.block.state.properties.NoteBlockInstrument.class).getSoundEvent(); // Paper - move NotePlayEvent call to fix instrument/note changes
    }
    // Paper end - move NotePlayEvent call to fix instrument/note changes
    // config: blockUpdates.disableNoteblockUpdates (default false) not ported.
}
