package net.fissionmc.backend.mixin.world.entity.animal.equine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/animal/equine/AbstractHorse.java.patch */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin {
    @Shadow @Final private static int FLAG_OPEN_MOUTH;

    @Shadow protected abstract boolean getFlag(int flag);
    @Shadow protected abstract void setFlag(int flag, boolean value);

    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private int maxDomestication = 100; // CraftBukkit - store max domestication value

    @Shadow protected SimpleContainer inventory;

    // CraftBukkit - return stored max domestication instead
    @Inject(method = "getMaxTemper", at = @At("HEAD"), cancellable = true)
    private void paper$maxTemper(final CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.maxDomestication);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void paper$saveMaxDomestication(final ValueOutput output, final CallbackInfo ci) {
        output.putInt("Bukkit.MaxDomestication", this.maxDomestication); // Paper - max domestication
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void paper$loadMaxDomestication(final ValueInput input, final CallbackInfo ci) {
        this.maxDomestication = input.getIntOr("Bukkit.MaxDomestication", ((Object) this) instanceof net.minecraft.world.entity.animal.equine.Llama ? 30 : 100); // Paper - max domestication
    }

    // CraftBukkit - the horse inventory has the horse as its InventoryHolder
    @Inject(method = "createInventory", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/animal/equine/AbstractHorse;inventory:Lnet/minecraft/world/SimpleContainer;", opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void paper$inventoryHolder(final CallbackInfo ci) {
        this.inventory.bukkitOwner = (org.bukkit.entity.AbstractHorse) ((AbstractHorse) (Object) this).getBukkitEntity();
    }

    // CraftBukkit - horse jump event
    @Inject(method = "handleStartJump", at = @At("HEAD"), cancellable = true)
    private void paper$horseJump(final int jumpScale, final CallbackInfo ci) {
        float power;
        if (jumpScale >= 90) {
            power = 1.0F;
        } else {
            power = 0.4F + 0.4F * (float) jumpScale / 90.0F;
        }
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callHorseJumpEvent((AbstractHorse) (Object) this, power)) {
            ci.cancel();
        }
    }

    // CraftBukkit - regain reasons; Paper - Fix EntityBreedEvent copying
    @WrapOperation(method = "handleEating", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/equine/AbstractHorse;heal(F)V"))
    private void paper$healEating(final AbstractHorse self, final float amount, final Operation<Void> original) {
        self.heal(amount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.EATING);
    }

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/equine/AbstractHorse;heal(F)V"))
    private void paper$healRegen(final AbstractHorse self, final float amount, final Operation<Void> original) {
        self.heal(amount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN);
    }

    @WrapOperation(method = "handleEating", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/equine/AbstractHorse;setInLove(Lnet/minecraft/world/entity/player/Player;)V"))
    private void paper$setInLoveWithItem(final AbstractHorse self, final Player player, final Operation<Void> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) final ItemStack itemStack) {
        self.setInLove(player, itemStack.copy()); // Paper - Fix EntityBreedEvent copying
    }

    // Paper start - Horse API
    public void setMouthOpen(boolean open) {
        this.setFlag(AbstractHorseMixin.FLAG_OPEN_MOUTH, open);
    }

    public boolean isMouthOpen() {
        return this.getFlag(AbstractHorseMixin.FLAG_OPEN_MOUTH);
    }
    // Paper end - Horse API
}
