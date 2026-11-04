package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/block/SignBlock.java.patch */
@Mixin(SignBlock.class)
public abstract class SignBlockMixin {
    @WrapOperation(method = "useWithoutItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/SignBlock;openTextEdit(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;)V"))
    private void fission$openOnInteract(final SignBlock self, final Player player, final SignBlockEntity sign, final SignTextSlot slot, final Operation<Void> original) {
        this.openTextEdit(player, sign, slot, io.papermc.paper.event.player.PlayerOpenSignEvent.Cause.INTERACT); // Paper - Add PlayerOpenSignEvent
    }

    @Inject(method = "openTextEdit(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;)V", at = @At("HEAD"), cancellable = true)
    private void fission$openTextEdit(final Player player, final SignBlockEntity sign, final SignTextSlot slot, final CallbackInfo ci) {
        // Paper start - Add PlayerOpenSignEvent
        this.openTextEdit(player, sign, slot, io.papermc.paper.event.player.PlayerOpenSignEvent.Cause.UNKNOWN);
        ci.cancel();
    }

    public void openTextEdit(Player player, SignBlockEntity sign, SignTextSlot slot, io.papermc.paper.event.player.PlayerOpenSignEvent.Cause cause) {
        org.bukkit.entity.Player bukkitPlayer = (org.bukkit.entity.Player) player.getBukkitEntity();
        org.bukkit.block.Block bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at(sign.getLevel(), sign.getBlockPos());
        org.bukkit.craftbukkit.block.CraftSign<?> bukkitSign = (org.bukkit.craftbukkit.block.CraftSign<?>) org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(bukkitBlock);
        io.papermc.paper.event.player.PlayerOpenSignEvent event = new io.papermc.paper.event.player.PlayerOpenSignEvent(
            bukkitPlayer,
            bukkitSign,
            org.bukkit.craftbukkit.block.sign.CraftSignSide.fromVanilla(slot),
            cause);
        if (!event.callEvent()) return;
        if (org.bukkit.event.player.PlayerSignOpenEvent.getHandlerList().getRegisteredListeners().length > 0) {
            final org.bukkit.event.player.PlayerSignOpenEvent.Cause legacyCause = switch (cause) {
                case PLACE -> org.bukkit.event.player.PlayerSignOpenEvent.Cause.PLACE;
                case PLUGIN -> org.bukkit.event.player.PlayerSignOpenEvent.Cause.PLUGIN;
                case INTERACT -> org.bukkit.event.player.PlayerSignOpenEvent.Cause.INTERACT;
                case UNKNOWN -> org.bukkit.event.player.PlayerSignOpenEvent.Cause.UNKNOWN;
            };
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerSignOpenEvent(player, sign, slot, legacyCause)) {
        // Paper end - Add PlayerOpenSignEvent
            return;
        }
        } // Paper - Add PlayerOpenSignEvent
        sign.setAllowedPlayerEditor(player.getUUID());
        player.openTextEdit(sign, slot);
    }

    @Inject(method = "getTicker", at = @At("HEAD"), cancellable = true)
    private <T extends net.minecraft.world.level.block.entity.BlockEntity> void fission$noTicker(final Level level, final BlockState blockState, final BlockEntityType<T> type, final CallbackInfoReturnable<BlockEntityTicker<T>> cir) {
        cir.setReturnValue(null); // CraftBukkit - remove unnecessary sign ticking
    }

    // Paper - Add PlayerOpenSignEvent - call later in ItemStack#useOn
    @WrapWithCondition(method = "setPlacedBy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/SignBlock;openTextEdit(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;)V"))
    private boolean fission$noOpenOnPlace(final SignBlock self, final Player player, final SignBlockEntity sign, final SignTextSlot slot) {
        return false;
    }
}
