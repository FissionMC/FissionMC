package net.fissionmc.backend.mixin.world.level;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/ServerExplosion.java.patch */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private @Nullable Entity source;
    @Shadow @Final private float radius;
    @Shadow @Final private DamageSource damageSource;
    @Shadow @Final private ExplosionDamageCalculator damageCalculator;
    @Shadow @Final private Map<Player, Vec3> hitPlayers;
    @Shadow @Final private Explosion.BlockInteraction blockInteraction;
    @Shadow public abstract Explosion.BlockInteraction getBlockInteraction();

    // CraftBukkit - add field
    private boolean wasCanceled = false;
    private float yield;
    // CraftBukkit end
    private boolean excludeSourceFromDamage = true; // Paper - Allow explosions to damage source

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$yield(final CallbackInfo ci) {
        // Paper start - add yield
        this.yield = this.blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY ? 1.0F / this.radius : 1.0F;
        this.yield = Double.isFinite(this.yield) ? this.yield : 0; // Paper - Don't allow infinite default yields
        // Paper end - add yield
    }

    @WrapOperation(method = "calculateExplodedPositions", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;shouldBlockExplode(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)Z"))
    private boolean paper$protectAndHeadlessPistons(final ExplosionDamageCalculator calculator, final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState block, final float power,
                                                    final Operation<Boolean> original, @Local final Set<BlockPos> toBlowSet) {
        if (!net.fissionmc.backend.server.PaperDestroyable.isDestroyable(block) || !original.call(calculator, explosion, level, pos, block, power)) { // Paper - Protect Bedrock and End Portal/Frames from being destroyed
            return false;
        }
        // Paper start - prevent headless pistons from forming
        if (!io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowHeadlessPistons && block.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
            net.minecraft.world.level.block.entity.BlockEntity extension = this.level.getBlockEntity(pos);
            if (extension instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity blockEntity && blockEntity.isSourcePiston()) {
                net.minecraft.core.Direction direction = block.getValue(net.minecraft.world.level.block.piston.PistonHeadBlock.FACING);
                toBlowSet.add(pos.relative(direction.getOpposite()));
            }
        }
        // Paper end - prevent headless pistons from forming
        return true;
    }

    // FissionMC: replaced body (CraftBukkit/Paper rewrite the entity loop: dragon parts, cancelled damage, knockback events)
    // config: environment.optimize-explosions (density cache), environment.disable-explosion-knockback (ported, default off)
    @Inject(method = "hurtEntities", at = @At("HEAD"), cancellable = true)
    private void paper$hurtEntities(final CallbackInfo ci) {
        ci.cancel();
        final ServerExplosion self = (ServerExplosion) (Object) this;
        if (!(this.radius < 1.0E-5F)) {
            double doubleRadius = this.radius * 2.0F;
            int x0 = Mth.floor(this.center.x - doubleRadius - 1.0);
            int x1 = Mth.floor(this.center.x + doubleRadius + 1.0);
            int y0 = Mth.floor(this.center.y - doubleRadius - 1.0);
            int y1 = Mth.floor(this.center.y + doubleRadius + 1.0);
            int z0 = Mth.floor(this.center.z - doubleRadius - 1.0);
            int z1 = Mth.floor(this.center.z + doubleRadius + 1.0);
            List<Entity> list = this.level.getEntities(this.excludeSourceFromDamage ? this.source : null, new AABB(x0, y0, z0, x1, y1, z1), entity -> entity.isAlive() && !entity.isSpectator()); // Paper - Fix lag from explosions processing dead entities, Allow explosions to damage source

            for (Entity entity : list) { // Paper - used in loop
                if (!entity.ignoreExplosion(self)) {
                    double dist = Math.sqrt(entity.distanceToSqr(this.center)) / doubleRadius;
                    if (!(dist > 1.0)) {
                        Vec3 entityOrigin = entity instanceof net.minecraft.world.entity.item.PrimedTnt ? entity.position() : entity.getEyePosition();
                        Vec3 direction = entityOrigin.subtract(this.center).normalize();
                        boolean shouldDamageEntity = this.damageCalculator.shouldDamageEntity(self, entity);
                        float knockbackMultiplier = this.damageCalculator.getKnockbackMultiplier(entity);
                        float exposure = !shouldDamageEntity && knockbackMultiplier == 0.0F ? 0.0F : ServerExplosion.getSeenPercent(this.center, entity); // FissionMC: Paper - Optimize explosions (getBlockDensity) only differs with optimize-explosions
                        if (shouldDamageEntity && (this.source == null || this.source.doTeamsAllowDamage(entity))) {
                            // CraftBukkit start

                            // Special case ender dragon only give knockback if no damage is cancelled
                            // Thinks to note:
                            // - Setting a velocity to a EnderDragonPart is ignored (and therefore not needed)
                            // - Damaging EnderDragonPart while forward the damage to EnderDragon
                            // - Damaging EnderDragon does nothing
                            // - EnderDragon hitbox always covers the other parts and is therefore always present
                            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart) {
                                continue;
                            }

                            entity.lastDamageCancelled = false;

                            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon enderDragon) {
                                for (net.minecraft.world.entity.boss.enderdragon.EnderDragonPart dragonPart : enderDragon.getSubEntities()) {
                                    // Calculate damage separately for each EntityComplexPart
                                    if (list.contains(dragonPart)) {
                                        dragonPart.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(self, dragonPart, exposure));
                                    }
                                }
                            } else {
                                entity.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(self, entity, exposure));
                            }

                            if (entity.lastDamageCancelled) { // SPIGOT-5339, SPIGOT-6252, SPIGOT-6777: Skip entity if damage event was cancelled
                                continue;
                            }
                            // CraftBukkit end
                        }

                        double knockbackResistance = entity instanceof LivingEntity livingEntity
                            ? livingEntity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                            : 0.0;
                        double knockbackPower = entity instanceof Player && this.level.paperConfig().environment.disableExplosionKnockback ? 0 : (1.0 - dist) * exposure * knockbackMultiplier * (1.0 - knockbackResistance); // Paper
                        Vec3 knockback = direction.scale(knockbackPower);
                        // CraftBukkit start - Call EntityKnockbackEvent
                        if (entity instanceof LivingEntity livingEntity) {
                            // Paper start - knockback events
                            io.papermc.paper.event.entity.EntityKnockbackEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityKnockbackEvent(livingEntity.getBukkitEntity(), this.source, this.damageSource.getEntity() != null ? this.damageSource.getEntity() : this.source, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION, knockbackPower, knockback);
                            knockback = event.isCancelled() ? Vec3.ZERO : org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getKnockback());
                            // Paper end - knockback events
                        }
                        // CraftBukkit end
                        entity.pushFromExplosion(knockback);
                        if (entity.is(EntityTypeTags.REDIRECTABLE_PROJECTILE) && entity instanceof Projectile projectile) {
                            projectile.setOwner(this.damageSource.getEntity());
                        } else if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying) && !this.level.paperConfig().environment.disableExplosionKnockback) { // Paper - Option to disable explosion knockback
                            this.hitPlayers.put(player, knockback);
                        }

                        entity.onExplosionHit(this.source);
                    }
                }
            }
        }
    }

    // CraftBukkit start - EntityExplodeEvent / BlockExplodeEvent
    @Inject(method = "interactWithBlocks", at = @At("HEAD"), cancellable = true)
    private void paper$explodeEvent(final List<BlockPos> targetBlocks, final CallbackInfo ci) {
        // FissionMC: Paper fires this after shuffling targetBlocks; the shuffle uses the level random the same way afterwards
        net.minecraft.util.Util.shuffle(targetBlocks, this.level.getRandom());
        org.bukkit.Location location = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.center, this.level);
        List<org.bukkit.block.Block> blockList = new ObjectArrayList<>();
        for (int i1 = targetBlocks.size() - 1; i1 >= 0; i1--) {
            org.bukkit.block.Block bblock = org.bukkit.craftbukkit.block.CraftBlock.at(this.level, targetBlocks.get(i1));
            if (!bblock.getType().isAir()) {
                blockList.add(bblock);
            }
        }

        List<org.bukkit.block.Block> bukkitBlocks;

        if (this.source != null) {
            org.bukkit.event.entity.EntityExplodeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityExplodeEvent(this.source, blockList, this.yield, this.getBlockInteraction());
            this.wasCanceled = event.isCancelled();
            bukkitBlocks = event.blockList();
            this.yield = event.getYield();
        } else {
            org.bukkit.block.Block block = location.getBlock();
            org.bukkit.block.BlockState blockState = (this.damageSource.causingBlockSnapshot() != null) ? this.damageSource.causingBlockSnapshot() : block.getState();
            org.bukkit.event.block.BlockExplodeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockExplodeEvent(block, blockState, blockList, this.yield, this.getBlockInteraction());
            this.wasCanceled = event.isCancelled();
            bukkitBlocks = event.blockList();
            this.yield = event.getYield();
        }

        targetBlocks.clear();
        for (org.bukkit.block.Block bblock : bukkitBlocks) {
            targetBlocks.add(((org.bukkit.craftbukkit.block.CraftBlock) bblock).getPosition());
        }

        if (this.wasCanceled) {
            ci.cancel();
        }
    }
    // CraftBukkit end

    // FissionMC: the vanilla shuffle already happened in paper$explodeEvent (Paper shuffles before building the event list)
    @WrapWithCondition(method = "interactWithBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V"))
    private boolean paper$shuffledAlready(final List<?> list, final net.minecraft.util.RandomSource random) {
        return false;
    }

    // CraftBukkit start - TNTPrimeEvent
    @WrapWithCondition(method = "interactWithBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;onExplosionHit(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Explosion;Ljava/util/function/BiConsumer;)V"))
    private boolean paper$tntPrimeEvent(final BlockState state, final ServerLevel level, final BlockPos pos, final Explosion explosion, final java.util.function.BiConsumer<?, ?> onHit) {
        Block block = state.getBlock();
        if (level.getGameRules().get(GameRules.TNT_EXPLODES) && block instanceof net.minecraft.world.level.block.TntBlock) {
            Entity sourceEntity = this.source == null ? null : this.source;
            BlockPos sourceBlock = sourceEntity == null ? BlockPos.containing(this.center) : null;
            if (!org.bukkit.craftbukkit.event.CraftEventFactory.callTNTPrimeEvent(this.level, pos, org.bukkit.event.block.TNTPrimeEvent.PrimeCause.EXPLOSION, sourceEntity, sourceBlock)) {
                this.level.sendBlockUpdated(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), state, Block.UPDATE_ALL); // Update the block on the client
                return false;
            }
        }
        return true;
    }
    // CraftBukkit end

    // CraftBukkit start - Ignition by explosion
    @WrapWithCondition(method = "createFire", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean paper$igniteEvent(final ServerLevel level, final BlockPos pos, final BlockState state) {
        return !org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(this.level, pos, (ServerExplosion) (Object) this).isCancelled();
    }
    // CraftBukkit end
}
