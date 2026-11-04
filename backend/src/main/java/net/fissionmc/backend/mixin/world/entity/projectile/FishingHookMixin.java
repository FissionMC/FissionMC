package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Paper: sources/net/minecraft/world/entity/projectile/FishingHook.java.patch */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Shadow private int timeUntilLured;
    @Shadow @Final private int lureSpeed;

    @Shadow public abstract int retrieve(ItemStack rod);

    // CraftBukkit start - Extra variables to enable modification of fishing wait time, values are minecraft defaults
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private int minWaitTime = 100;
    private int maxWaitTime = 600;
    private int minLureTime = 20;
    private int maxLureTime = 80;
    private float minLureAngle = 0.0F;
    private float maxLureAngle = 360.0F;
    private boolean applyLure = true;
    private boolean rainInfluenced = true;
    private boolean skyInfluenced = true;
    // CraftBukkit end
    // TODO(behaviour): constructor sets minWaitTime/maxWaitTime from level.paperConfig().fishingTimeRange;
    //  catchingFish uses rain/skyInfluenced, min/maxLureAngle, min/maxLureTime, calls resetTimeUntilLured() and fires PlayerFishEvent/FishHookStateChangeEvent.

    @Unique private InteractionHand fission$retrieveHand = InteractionHand.MAIN_HAND;

    // Paper start - more projectile api - extract time until lured reset logic
    public void resetTimeUntilLured() {
        this.timeUntilLured = Mth.nextInt(((Entity) (Object) this).getRandom(), this.minWaitTime, this.maxWaitTime);
        this.timeUntilLured -= (this.applyLure) ? (this.lureSpeed >= this.maxWaitTime ? this.timeUntilLured - 1 : this.lureSpeed ) : 0; // Paper - Fix Lure infinite loop
    }
    // Paper end - more projectile api - extract time until lured reset logic

    // Paper start - Add hand parameter to PlayerFishEvent
    public int retrieve(final ItemStack rod, final InteractionHand hand) {
        // TODO(behaviour): Paper's retrieve body fires PlayerFishEvent (CAUGHT_ENTITY/CAUGHT_FISH/IN_GROUND/REEL_IN) with this hand,
        //  custom exp, re-checks hookedIn; vanilla retrieve(ItemStack) should read fission$retrieveHand.
        InteractionHand previous = this.fission$retrieveHand;
        this.fission$retrieveHand = hand;
        try {
            return this.retrieve(rod);
        } finally {
            this.fission$retrieveHand = previous;
        }
    }
    // Paper end - Add hand parameter to PlayerFishEvent
}
