package net.fissionmc.backend.mixin.world.entity.animal.golem;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.fissionmc.backend.support.ShearSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/animal/golem/CopperGolem.java.patch (PlayerShearEntityEvent in mobInteract, custom shear drops: the antenna item drop is replaced by the event drops) */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin {
    private static final String SHEAR = "Lnet/minecraft/world/entity/animal/golem/CopperGolem;shear(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/sounds/SoundSource;Lnet/minecraft/world/item/ItemStack;)V";

    @Unique private List<ItemStack> fission$shearDrops;

    // Paper start - custom shear drops
    public List<ItemStack> generateDefaultDrops(final ServerLevel level, final ItemStack tool) {
        final CopperGolem self = (CopperGolem) (Object) this;
        if (!self.readyForShearing()) {
            return java.util.Collections.emptyList();
        }
        final List<ItemStack> drops = ShearSupport.newDrops();
        drops.add(self.getItemBySlot(CopperGolem.EQUIPMENT_SLOT_ANTENNA).copy());
        return drops;
    }

    public void shear(final ServerLevel level, final SoundSource soundSource, final ItemStack tool, final List<ItemStack> drops) {
        this.fission$shearDrops = drops;
        try {
            ((CopperGolem) (Object) this).shear(level, soundSource, tool);
        } finally {
            this.fission$shearDrops = null;
        }
    }
    // Paper end - custom shear drops

    @WrapOperation(method = "shear(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/sounds/SoundSource;Lnet/minecraft/world/item/ItemStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/golem/CopperGolem;spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private ItemEntity fission$customDrops(final CopperGolem self, final ServerLevel level, final ItemStack stack, final float offset, final Operation<ItemEntity> original) {
        final List<ItemStack> drops = this.fission$shearDrops;
        if (drops == null) {
            return original.call(self, level, stack, offset);
        }
        for (final ItemStack drop : drops) {
            self.spawnAtLocation(level, drop, offset);
        }
        return null;
    }

    // Paper start - call PlayerShearEntityEvent
    @Inject(method = "mobInteract", at = @At(value = "INVOKE", target = SHEAR), cancellable = true)
    private void paper$shearEvent(final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir, @Local(ordinal = 0) final ItemStack itemStack, @Local final ServerLevel serverLevel) {
        final List<ItemStack> drops = ShearSupport.fire(player, (CopperGolem) (Object) this, itemStack, hand, this.generateDefaultDrops(serverLevel, itemStack));
        if (drops == null) {
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }
        this.fission$shearDrops = drops;
    }

    @WrapOperation(method = "mobInteract", at = @At(value = "INVOKE", target = SHEAR))
    private void paper$shearApply(final CopperGolem self, final ServerLevel level, final SoundSource soundSource, final ItemStack tool, final Operation<Void> original) {
        try {
            original.call(self, level, soundSource, tool);
        } finally {
            this.fission$shearDrops = null;
        }
    }
    // Paper end - call PlayerShearEntityEvent
}
