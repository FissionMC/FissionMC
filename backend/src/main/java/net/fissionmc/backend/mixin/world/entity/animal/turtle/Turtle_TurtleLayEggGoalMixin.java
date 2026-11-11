package net.fissionmc.backend.mixin.world.entity.animal.turtle;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TurtleEggBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/animal/turtle/Turtle.java.patch (TurtleLayEggGoal.tick: TurtleStartDiggingEvent, TurtleLayEggEvent + EntityChangeBlockEvent). */
@Mixin(targets = "net.minecraft.world.entity.animal.turtle.Turtle$TurtleLayEggGoal")
public abstract class Turtle_TurtleLayEggGoalMixin extends net.minecraft.world.entity.ai.goal.MoveToBlockGoal {
    // inherited protected blockPos is read through the superclass (a @Shadow of an inherited field is not located by Mixin)
    private Turtle_TurtleLayEggGoalMixin() { super(null, 0.0, 0); }

    @Shadow @Final private Turtle turtle;
    private int fission$eggCount;

    // Paper start - Turtle API
    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/turtle/Turtle;setLayingEgg(Z)V", ordinal = 0), index = 0)
    private boolean fission$startDigging(boolean laying) {
        return new com.destroystokyo.paper.event.entity.TurtleStartDiggingEvent((org.bukkit.entity.Turtle) this.turtle.getBukkitEntity(), org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.blockPos, this.turtle.level())).callEvent();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/turtle/Turtle;level()Lnet/minecraft/world/level/Level;", ordinal = 0), cancellable = true)
    private void fission$layEggEvent(CallbackInfo ci) {
        int eggCount = this.turtle.getRandom().nextInt(4) + 1;
        com.destroystokyo.paper.event.entity.TurtleLayEggEvent layEggEvent = new com.destroystokyo.paper.event.entity.TurtleLayEggEvent((org.bukkit.entity.Turtle) this.turtle.getBukkitEntity(), org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.blockPos.above(), this.turtle.level()), eggCount);
        if (layEggEvent.callEvent() && org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this.turtle, this.blockPos.above(), Blocks.TURTLE_EGG.defaultBlockState().setValue(TurtleEggBlock.EGGS, layEggEvent.getEggCount()))) {
            this.fission$eggCount = layEggEvent.getEggCount();
        } else {
            // cancelled: Paper only skips the egg placement, the turtle still resets
            this.turtle.setHasEgg(false);
            ((net.minecraft.world.entity.animal.turtle.Turtle) this.turtle).fission$setLayingEgg(false);
            this.turtle.setInLoveTime(600);
            ci.cancel();
        }
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;setValue(Lnet/minecraft/world/level/block/state/properties/Property;Ljava/lang/Comparable;)Ljava/lang/Object;"), index = 1)
    private Comparable<?> fission$eggCount(Comparable<?> eggs) {
        return this.fission$eggCount; // Paper
    }
    // Paper end - Turtle API
}
