package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Members Paper adds to {@link ExperienceOrb} (paper-server/patches/sources/net/minecraft/world/entity/ExperienceOrb.java.patch).
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    @Shadow private int count;
    @Shadow private int repairPlayerItems(net.minecraft.server.level.ServerPlayer player, int amount) { throw new AssertionError(); }
    @Shadow private static boolean tryMergeToExisting(ServerLevel level, Vec3 pos, int value) { throw new AssertionError(); }

    // Paper start
    // TODO(behaviour): loadPaperNBT/savePaperNBT ("Paper.ExpData") in read/addAdditionalSaveData; followNearbyPlayer target event
    private java.util.@Nullable UUID sourceEntityId;
    private java.util.@Nullable UUID triggerEntityId;
    private org.bukkit.entity.ExperienceOrb.SpawnReason spawnReason = org.bukkit.entity.ExperienceOrb.SpawnReason.UNKNOWN;
    // Paper end

    // FissionMC: static factories replacing Paper's constructors // Paper start - add reasons for orbs
    private static ExperienceOrb create(final Level level, final double x, final double y, final double z, final int value, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
        return create(level, new Vec3(x, y, z), Vec3.ZERO, value, reason, triggerId, sourceId);
    }

    private static ExperienceOrb create(final Level level, final Vec3 pos, final Vec3 roughly, final int value, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
        final ExperienceOrb orb = new ExperienceOrb(level, pos, roughly, value);
        final ExperienceOrbMixin paper = (ExperienceOrbMixin) (Object) orb;
        paper.sourceEntityId = sourceId != null ? sourceId.getUUID() : null;
        paper.triggerEntityId = triggerId != null ? triggerId.getUUID() : null;
        paper.spawnReason = reason != null ? reason : org.bukkit.entity.ExperienceOrb.SpawnReason.UNKNOWN;
        return orb;
    }
    // Paper end - add reasons for orbs

    // TODO(behaviour): vanilla awardWithDirection(level, pos, dir, amount) should delegate here with null reasons
    private static void awardWithDirection(final ServerLevel level, final Vec3 pos, final Vec3 roughDirection, int amount, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
        // Paper end - add reason to orbs
        while (amount > 0) {
            int newCount = ExperienceOrb.getExperienceValue(amount);
            amount -= newCount;
            if (!tryMergeToExisting(level, pos, newCount)) {
                level.addFreshEntity(create(level, pos, roughDirection, newCount, reason, triggerId, sourceId)); // Paper - add reason to orbs
            }
        }
    }

    // Paper: playerTouch - PlayerPickupExperienceEvent, PlayerExpCooldownChangeEvent, PlayerExpChangeEvent, EntityRemoveEvent.Cause.PICKUP
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void fission$playerTouch(final net.minecraft.world.entity.player.Player player, final CallbackInfo ci) {
        ci.cancel();
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return;
        }
        final ExperienceOrb self = (ExperienceOrb) (Object) this;
        if (player.takeXpDelay == 0 && new com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent(serverPlayer.getBukkitEntity(), (org.bukkit.entity.ExperienceOrb) self.getBukkitEntity()).callEvent()) {
            player.takeXpDelay = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerXpCooldownEvent(player, 2, org.bukkit.event.player.PlayerExpCooldownChangeEvent.ChangeReason.PICKUP_ORB).getNewCooldown();
            player.take(self, 1);
            final int remaining = this.repairPlayerItems(serverPlayer, self.getValue());
            if (remaining > 0) {
                player.giveExperiencePoints(org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerExpChangeEvent(player, self, remaining).getAmount());
            }
            this.count--;
            if (this.count == 0) {
                self.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP);
            }
        }
    }

    // Paper: repairPlayerItems - PlayerItemMendEvent
    @Inject(method = "repairPlayerItems", at = @At("HEAD"), cancellable = true)
    private void fission$repairPlayerItems(final net.minecraft.server.level.ServerPlayer player, final int amount, final CallbackInfoReturnable<Integer> cir) {
        final ExperienceOrb self = (ExperienceOrb) (Object) this;
        final java.util.Optional<net.minecraft.world.item.enchantment.EnchantedItemInUse> selected = net.minecraft.world.item.enchantment.EnchantmentHelper.getRandomItemWith(
            net.minecraft.world.item.enchantment.EnchantmentEffectComponents.REPAIR_WITH_XP, player, net.minecraft.world.item.ItemStack::isDamaged);
        if (selected.isEmpty()) {
            cir.setReturnValue(amount);
            return;
        }
        final net.minecraft.world.item.ItemStack itemStack = selected.get().itemStack();
        final int toRepairFromXpAmount = net.minecraft.world.item.enchantment.EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(), itemStack, amount);
        int repair = Math.min(toRepairFromXpAmount, itemStack.getDamageValue());
        final int consumedExperience = repair > 0 ? repair * amount / toRepairFromXpAmount : 0;
        final org.bukkit.event.player.PlayerItemMendEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerItemMendEvent(player, self, itemStack, selected.get().inSlot(), repair, consumedExperience);
        repair = event.getRepairAmount();
        if (event.isCancelled()) {
            cir.setReturnValue(amount);
            return;
        }
        itemStack.setDamageValue(itemStack.getDamageValue() - repair);
        if (repair > 0) {
            final int remaining = amount - repair * amount / toRepairFromXpAmount;
            if (remaining > 0) {
                cir.setReturnValue(this.repairPlayerItems(player, remaining));
                return;
            }
        }
        cir.setReturnValue(0);
    }

    // CraftBukkit: larger orb value tiers
    @Inject(method = "getExperienceValue", at = @At("HEAD"), cancellable = true)
    private static void fission$getExperienceValue(final int maxValue, final CallbackInfoReturnable<Integer> cir) {
        final int[] tiers = {162670129, 81335063, 40667527, 20333759, 10166857, 5083423, 2541701, 1270849, 635413, 317701, 158849, 79423, 39709, 19853, 9923, 4957};
        if (maxValue > tiers[0]) {
            cir.setReturnValue(maxValue - 100000);
            return;
        }
        for (int i = 1; i < tiers.length; i++) {
            if (maxValue > tiers[i]) {
                cir.setReturnValue(tiers[i]);
                return;
            }
        }
    }
}
