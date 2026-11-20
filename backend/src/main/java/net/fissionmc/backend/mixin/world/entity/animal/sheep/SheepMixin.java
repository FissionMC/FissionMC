package net.fissionmc.backend.mixin.world.entity.animal.sheep;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.function.BiConsumer;
import net.fissionmc.backend.support.ShearSupport;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.entity.animal.sheep.Sheep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/entity/animal/sheep/Sheep.java.patch
 * done: SheepRegrowWoolEvent. Not ported: PlayerShearEntityEvent / custom shear drops in mobInteract.
 */
@Mixin(Sheep.class)
public abstract class SheepMixin {
    @Inject(method = "ate", at = @At("HEAD"), cancellable = true)
    private void paper$regrowWool(final CallbackInfo ci) {
        if (!new org.bukkit.event.entity.SheepRegrowWoolEvent((org.bukkit.entity.Sheep) ((Sheep) (Object) this).getBukkitEntity()).callEvent()) ci.cancel(); // CraftBukkit
    }

    private static final String SHEAR = "Lnet/minecraft/world/entity/animal/sheep/Sheep;shear(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/sounds/SoundSource;Lnet/minecraft/world/item/ItemStack;)V";

    @Unique private List<ItemStack> fission$shearDrops;

    // Paper start - custom shear drops
    public List<ItemStack> generateDefaultDrops(final ServerLevel level, final ItemStack tool) {
        final List<ItemStack> drops = ShearSupport.newDrops();
        ((Sheep) (Object) this).dropFromShearingLootTable(level, BuiltInLootTables.SHEAR_SHEEP, tool, ShearSupport.splitCollector(drops));
        return drops;
    }

    public void shear(final ServerLevel level, final SoundSource soundSource, final ItemStack tool, final List<ItemStack> drops) {
        this.fission$shearDrops = drops;
        try {
            ((Sheep) (Object) this).shear(level, soundSource, tool);
        } finally {
            this.fission$shearDrops = null;
        }
    }
    // Paper end - custom shear drops

    @WrapOperation(method = "shear(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/sounds/SoundSource;Lnet/minecraft/world/item/ItemStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/sheep/Sheep;dropFromShearingLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/item/ItemInstance;Ljava/util/function/BiConsumer;)V"))
    private void fission$customDrops(final Sheep self, final ServerLevel level, final ResourceKey<LootTable> table, final ItemInstance tool, final BiConsumer<ServerLevel, ItemStack> consumer, final Operation<Void> original) {
        final List<ItemStack> drops = this.fission$shearDrops;
        if (drops == null) {
            original.call(self, level, table, tool, consumer);
        } else {
            ShearSupport.deliver(drops, level, consumer);
        }
    }

    // Paper start - call PlayerShearEntityEvent
    @Inject(method = "mobInteract", at = @At(value = "INVOKE", target = SHEAR), cancellable = true)
    private void paper$shearEvent(final Player player, final InteractionHand hand, final CallbackInfoReturnable<InteractionResult> cir, @Local(ordinal = 0) final ItemStack itemStack, @Local final ServerLevel level) {
        final List<ItemStack> drops = ShearSupport.fire(player, (Sheep) (Object) this, itemStack, hand, this.generateDefaultDrops(level, itemStack));
        if (drops == null) {
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }
        this.fission$shearDrops = drops;
    }

    @WrapOperation(method = "mobInteract", at = @At(value = "INVOKE", target = SHEAR))
    private void paper$shearApply(final Sheep self, final ServerLevel level, final SoundSource soundSource, final ItemStack tool, final Operation<Void> original) {
        try {
            original.call(self, level, soundSource, tool);
        } finally {
            this.fission$shearDrops = null;
        }
    }
    // Paper end - call PlayerShearEntityEvent
}
