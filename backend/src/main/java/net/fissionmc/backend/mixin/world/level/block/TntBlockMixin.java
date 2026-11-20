package net.fissionmc.backend.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.block.TNTPrimeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/level/block/TntBlock.java.patch (TNTPrimeEvent).
 * Paper threads a BooleanSupplier through prime(); here the event is fired by wrapping each caller's prime() call instead
 * (the gamerule check is repeated so the event is not fired while TNT cannot explode).
 */
@Mixin(TntBlock.class)
public abstract class TntBlockMixin {
    private static boolean fission$canPrime(final Level level) {
        return level instanceof ServerLevel serverLevel && serverLevel.getGameRules().get(GameRules.TNT_EXPLODES);
    }

    @WrapOperation(method = {"onPlace", "neighborChanged"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/TntBlock;prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean paper$primeRedstone(final Level level, final BlockPos pos, final Operation<Boolean> original) {
        if (fission$canPrime(level) && !CraftEventFactory.callTNTPrimeEvent(level, pos, TNTPrimeEvent.PrimeCause.REDSTONE, null, null)) {
            return false;
        }
        return original.call(level, pos);
    }

    @WrapOperation(method = "playerWillDestroy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/TntBlock;prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean paper$primeBreak(final Level level, final BlockPos pos, final Operation<Boolean> original, @Local(argsOnly = true) final Player player) {
        if (fission$canPrime(level) && !CraftEventFactory.callTNTPrimeEvent(level, pos, TNTPrimeEvent.PrimeCause.BLOCK_BREAK, player, null)) {
            return false;
        }
        return original.call(level, pos);
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/TntBlock;prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean paper$primePlayer(final Level level, final BlockPos pos, final LivingEntity source, final ItemStack item, final Operation<Boolean> original) {
        if (fission$canPrime(level) && !CraftEventFactory.callTNTPrimeEvent(level, pos, TNTPrimeEvent.PrimeCause.PLAYER, source, null)) {
            return false;
        }
        return original.call(level, pos, source, item);
    }

    @WrapOperation(method = "onProjectileHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/TntBlock;prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean paper$primeProjectile(final Level level, final BlockPos pos, final LivingEntity source, final ItemStack item, final Operation<Boolean> original,
                                                  @Local(argsOnly = true) final BlockState state, @Local(argsOnly = true) final Projectile projectile) {
        if (fission$canPrime(level)
            && !(CraftEventFactory.callEntityChangeBlockEvent(projectile, pos, state.getFluidState().createLegacyBlock())
            && CraftEventFactory.callTNTPrimeEvent(level, pos, TNTPrimeEvent.PrimeCause.PROJECTILE, projectile, null))) {
            return false;
        }
        return original.call(level, pos, source, item);
    }
}
