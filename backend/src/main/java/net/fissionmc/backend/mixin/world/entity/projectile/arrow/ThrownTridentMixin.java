package net.fissionmc.backend.mixin.world.entity.projectile.arrow;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/projectile/arrow/ThrownTrident.java.patch */
@Mixin(ThrownTrident.class)
public abstract class ThrownTridentMixin extends AbstractArrow {
    @Shadow @Final private static EntityDataAccessor<Byte> ID_LOYALTY;
    @Shadow @Final private static EntityDataAccessor<Boolean> ID_FOIL;

    protected ThrownTridentMixin(final EntityType<? extends AbstractArrow> type, final Level level) {
        super(type, level);
    }

    // Paper start - Allow trident custom damage
    @Inject(method = "<init>*", at = @At("RETURN"))
    private void fission$baseDamage(CallbackInfo ci) {
        this.setBaseDamage(net.minecraft.world.item.TridentItem.BASE_DAMAGE);
    }

    @ModifyConstant(method = "onHitEntity", constant = @Constant(floatValue = 8.0F))
    private float fission$customDamage(float original) {
        return (float) this.baseDamage; // Paper - Allow trident custom damage
    }
    // Paper end - Allow trident custom damage

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/ThrownTrident;discard()V", ordinal = 0))
    private void fission$discardDrop(ThrownTrident instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DROP); // CraftBukkit - add Bukkit remove cause
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/ThrownTrident;discard()V", ordinal = 1))
    private void fission$discardDespawn(ThrownTrident instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    // Paper - pass custom pickup item
    @Redirect(method = "tryPickup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean fission$customPickupItem(Inventory inventory, ItemStack stack) {
        return inventory.add(net.fissionmc.backend.entitytypes.PickupItem.orElse(stack));
    }

    // Paper start
    public void setFoil(boolean foil) {
        this.entityData.set(ThrownTridentMixin.ID_FOIL, foil);
    }

    public int getLoyalty() {
        return this.entityData.get(ThrownTridentMixin.ID_LOYALTY);
    }

    public void setLoyalty(byte loyalty) {
        this.entityData.set(ThrownTridentMixin.ID_LOYALTY, loyalty);
    }
    // Paper end
}
