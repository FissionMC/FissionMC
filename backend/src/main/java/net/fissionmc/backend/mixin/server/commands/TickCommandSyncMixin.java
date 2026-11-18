package net.fissionmc.backend.mixin.server.commands;

import net.fissionmc.backend.relay.TickSyncBackend;
import net.fissionmc.protocol.TickAction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.TickCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FissionMC tick sync: a /tick action that SUCCEEDED here (a failing one throws before the return) is reported to the master, which owns the network's tick state and applies it on
 * every other backend through the TickRateManager. Applied changes run through the manager, not through this command, so nothing loops. New file.
 */
@Mixin(TickCommand.class)
public abstract class TickCommandSyncMixin {
    @Inject(method = "setTickingRate", at = @At("RETURN"))
    private static void fission$rate(CommandSourceStack source, float rate, CallbackInfoReturnable<Integer> cir) { TickSyncBackend.report(TickAction.RATE, rate); }

    @Inject(method = "setFreeze", at = @At("RETURN"))
    private static void fission$freeze(CommandSourceStack source, boolean freeze, CallbackInfoReturnable<Integer> cir) { TickSyncBackend.report(freeze ? TickAction.FREEZE : TickAction.UNFREEZE, 0f); }

    @Inject(method = "step", at = @At("RETURN"))
    private static void fission$step(CommandSourceStack source, int ticks, CallbackInfoReturnable<Integer> cir) { TickSyncBackend.report(TickAction.STEP, (float) ticks); }

    @Inject(method = "stopStepping", at = @At("RETURN"))
    private static void fission$stopStep(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) { TickSyncBackend.report(TickAction.STEP_STOP, 0f); }

    @Inject(method = "sprint", at = @At("RETURN"))
    private static void fission$sprint(CommandSourceStack source, int ticks, CallbackInfoReturnable<Integer> cir) { TickSyncBackend.report(TickAction.SPRINT, (float) ticks); }

    @Inject(method = "stopSprinting", at = @At("RETURN"))
    private static void fission$stopSprint(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) { TickSyncBackend.report(TickAction.SPRINT_STOP, 0f); }
}
