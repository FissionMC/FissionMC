package net.fissionmc.backend.mixin.world.entity.monster;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/monster/Witch.java.patch */
@Mixin(Witch.class)
public abstract class WitchMixin {
    @Shadow @Final private static Identifier SPEED_MODIFIER_DRINKING_ID;
    @Shadow @Final private static AttributeModifier SPEED_MODIFIER_DRINKING;
    @Shadow private int usingTime;

    // TODO(behaviour): aiStep should call setDrinkingPotion instead of its inlined copy; WitchThrowPotionEvent etc.

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
