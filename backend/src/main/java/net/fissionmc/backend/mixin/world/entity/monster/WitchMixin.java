package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.ItemStack;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/monster/Witch.java.patch */
@Mixin(Witch.class)
public abstract class WitchMixin {
    @Shadow @Final private static Identifier SPEED_MODIFIER_DRINKING_ID;
    @Shadow @Final private static AttributeModifier SPEED_MODIFIER_DRINKING;
    @Shadow private int usingTime;

    // Paper start - WitchConsumePotionEvent (aiStep, the potion that is drunk)
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object fission$consumePotionEvent(ItemStack itemStack, DataComponentType<?> type, Operation<Object> original) {
        Object potion = original.call(itemStack, type);
        if (type == DataComponents.POTION_CONTENTS && itemStack.is(Items.POTION)) {
            com.destroystokyo.paper.event.entity.WitchConsumePotionEvent event = new com.destroystokyo.paper.event.entity.WitchConsumePotionEvent((org.bukkit.entity.Witch) ((Witch) (Object) this).getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemStack));
            potion = event.callEvent() ? org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(event.getPotion()).get(DataComponents.POTION_CONTENTS) : null;
        }
        return potion;
    }
    // Paper end - WitchConsumePotionEvent

    // CraftBukkit: addEffect with the ATTACK cause
    @ModifyArg(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/alchemy/PotionContents;forEachEffect(Ljava/util/function/Consumer;F)V"), index = 0)
    private java.util.function.Consumer<net.minecraft.world.effect.MobEffectInstance> fission$drinkCause(java.util.function.Consumer<net.minecraft.world.effect.MobEffectInstance> original) {
        Witch self = (Witch) (Object) this;
        return effect -> self.addEffect(effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK);
    }

    // Paper: aiStep calls setDrinkingPotion (WitchReadyPotionEvent) for the potion it is about to drink
    @ModifyExpressionValue(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/alchemy/PotionContents;createItemStack(Lnet/minecraft/world/item/Item;Lnet/minecraft/core/Holder;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack fission$readyPotionEvent(ItemStack potion) {
        return org.bukkit.craftbukkit.event.CraftEventFactory.handleWitchReadyPotionEvent((Witch) (Object) this, potion);
    }

    // Paper start - WitchThrowPotionEvent
    @WrapOperation(method = "performRangedAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;spawnProjectileUsingShoot(Lnet/minecraft/world/entity/projectile/Projectile$ProjectileFactory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;DDDFF)Lnet/minecraft/world/entity/projectile/Projectile;"))
    private net.minecraft.world.entity.projectile.Projectile fission$throwPotionEvent(net.minecraft.world.entity.projectile.Projectile.ProjectileFactory<?> factory, net.minecraft.server.level.ServerLevel level, ItemStack itemStack, LivingEntity owner, double x, double y, double z, float power, float uncertainty, Operation<net.minecraft.world.entity.projectile.Projectile> original, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) LivingEntity target) {
        com.destroystokyo.paper.event.entity.WitchThrowPotionEvent event = new com.destroystokyo.paper.event.entity.WitchThrowPotionEvent((org.bukkit.entity.Witch) ((Witch) (Object) this).getBukkitEntity(), target.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitMirror(itemStack));
        if (!event.callEvent()) {
            return null; // Paper returns before the throw sound as well; the sound is not skipped here
        }
        itemStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getPotion());
        return original.call(factory, level, itemStack, owner, x, y, z, power, uncertainty);
    }
    // Paper end - WitchThrowPotionEvent

    // Paper start - moved to its own method
    public void setDrinkingPotion(ItemStack potion) {
        Witch self = (Witch) (Object) this;
        potion = org.bukkit.craftbukkit.event.CraftEventFactory.handleWitchReadyPotionEvent(self, potion);
        self.setItemSlot(EquipmentSlot.MAINHAND, potion);
        this.usingTime = self.getMainHandItem().getUseDuration(self);
        self.setUsingItem(true);
        if (!self.isSilent()) {
            self.level().playSound(null, self.getX(), self.getY(), self.getZ(), SoundEvents.WITCH_DRINK, self.getSoundSource(), 1.0F, 0.8F + self.getRandom().nextFloat() * 0.4F);
        }

        AttributeInstance speed = self.getAttribute(Attributes.MOVEMENT_SPEED);

        speed.removeModifier(WitchMixin.SPEED_MODIFIER_DRINKING_ID);
        speed.addTransientModifier(WitchMixin.SPEED_MODIFIER_DRINKING);
    }
    // Paper end
}
