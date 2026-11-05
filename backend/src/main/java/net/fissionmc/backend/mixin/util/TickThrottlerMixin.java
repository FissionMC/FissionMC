package net.fissionmc.backend.mixin.util;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.TickThrottler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/util/TickThrottler.java.patch (CraftBukkit - multithreaded field).
 * The vanilla int `count` is left unused; all accesses go to the atomic counter.
 */
@Mixin(TickThrottler.class)
public abstract class TickThrottlerMixin {
    @Shadow @Final private int incrementStep;
    @Shadow @Final private int threshold;
    @Unique private final AtomicInteger fission$count = new AtomicInteger(); // CraftBukkit - multithreaded field

    @Inject(method = "increment", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$increment(CallbackInfo ci) {
        this.fission$count.addAndGet(this.incrementStep); // CraftBukkit - use thread-safe field access instead
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$tick(CallbackInfo ci) {
        // CraftBukkit start
        for (int val; (val = this.fission$count.get()) > 0 && !this.fission$count.compareAndSet(val, val - 1); ) ;
        // CraftBukkit end
        ci.cancel();
    }

    @Inject(method = "isUnderThreshold", at = @At("HEAD"), cancellable = true)
    private void craftbukkit$isUnderThreshold(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.threshold <= 0 || this.fission$count.get() < this.threshold); // CraftBukkit - use thread-safe field access instead
    }

    // CraftBukkit start
    public boolean isIncrementAndUnderThreshold() {
        return this.isIncrementAndUnderThreshold(this.incrementStep, this.threshold);
    }

    public boolean isIncrementAndUnderThreshold(int incrementStep, int threshold) {
        return threshold <= 0 || this.fission$count.addAndGet(incrementStep) < threshold;
    }
    // CraftBukkit end
}
