package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * CraftBukkit/Paper: sources/net/minecraft/world/item/LeadItem.java.patch. Fires HangingPlaceEvent for a new leash knot and PlayerLeashEntityEvent per leashed mob.
 * Paper passes the InteractionHand through a new bindPlayerMobs parameter; here the hand is derived from the stack the player holds (main hand wins).
 * A cancelled HangingPlaceEvent is modelled as "no mob may be leashed to the knot": the vanilla tail then discards the new knot and returns PASS, as Paper does.
 */
@Mixin(LeadItem.class)
public abstract class LeadItemMixin {
    private static final ThreadLocal<Boolean> fission$knotCancelled = ThreadLocal.withInitial(() -> false);

    @WrapOperation(method = "bindPlayerMobs", at = @At(value = "INVOKE", target = "Ljava/util/Optional;orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;"))
    private static Object fission$knotPlaceEvent(final Optional<LeashFenceKnotEntity> existingKnot, final Supplier<LeashFenceKnotEntity> creator, final Operation<Object> original,
                                                 @Local(argsOnly = true) final Player player, @Local(argsOnly = true) final Level level, @Local(argsOnly = true) final BlockPos pos) {
        final LeashFenceKnotEntity activeKnot = (LeashFenceKnotEntity) original.call(existingKnot, creator);
        fission$knotCancelled.set(false);
        // CraftBukkit start - fire HangingPlaceEvent
        if (existingKnot.isEmpty()) {
            final org.bukkit.inventory.EquipmentSlot handSlot = org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(fission$hand(player));
            final org.bukkit.event.hanging.HangingPlaceEvent event = new org.bukkit.event.hanging.HangingPlaceEvent((org.bukkit.entity.Hanging) activeKnot.getBukkitEntity(), player != null ? (org.bukkit.entity.Player) player.getBukkitEntity() : null, org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), org.bukkit.block.BlockFace.SELF, handSlot);
            level.getCraftServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                fission$knotCancelled.set(true);
            }
        }
        // CraftBukkit end
        return activeKnot;
    }

    @WrapOperation(method = "bindPlayerMobs", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Leashable;canHaveALeashAttachedTo(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean fission$leashEvent(final Leashable leashable, final Entity knot, final Operation<Boolean> original, @Local(argsOnly = true) final Player player) {
        if (fission$knotCancelled.get()) {
            return false;
        }
        return original.call(leashable, knot) && org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerLeashEntityEvent(leashable, knot, player, fission$hand(player)); // Paper - leash event
    }

    private static InteractionHand fission$hand(final Player player) {
        return player != null && !player.getMainHandItem().is(Items.LEAD) && player.getOffhandItem().is(Items.LEAD) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }
}
