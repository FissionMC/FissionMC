package net.fissionmc.backend.mixin.world.level.chunk;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/level/chunk/LevelChunk.java.patch */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow public abstract BlockState getBlockState(BlockPos pos);
    @Shadow public abstract FluidState getFluidState(BlockPos pos);
    @Shadow @Final private Level level;
    @Shadow private boolean isInLevel() { throw new AssertionError(); }

    // CraftBukkit start
    private boolean mustNotSave;
    private boolean needsDecoration;
    // CraftBukkit end

    // Paper start
    private boolean loadedTicketLevel; // FissionMC: package-private in Paper, not declared in the surface
    // Paper end

    // Paper start - If loaded util
    public final FluidState getFluidIfLoaded(BlockPos pos) {
        return this.getFluidState(pos);
    }

    public final BlockState getBlockStateIfLoaded(BlockPos pos) {
        return this.getBlockState(pos);
    }
    // Paper end

    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("RETURN"))
    private void paper$fromProtoChunk(final ServerLevel level, final ProtoChunk protoChunk, final LevelChunk.PostLoadProcessor postLoad, final CallbackInfo ci) {
        this.needsDecoration = true; // CraftBukkit
        // CraftBukkit start
        ((LevelChunk) (Object) this).persistentDataContainer = protoChunk.persistentDataContainer; // SPIGOT-6814: copy PDC to account for 1.17 to 1.18 chunk upgrading.
        // CraftBukkit end
    }

    // CraftBukkit - Don't place while processing the BlockPlaceEvent, unless it's a BlockContainer. Prevents blocks such as TNT from activating when cancelled.
    @WrapWithCondition(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private boolean paper$noOnPlaceWhileCapturing(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
        return !this.level.captureBlockStates || state.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock;
    }

    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At("HEAD"), cancellable = true)
    private void paper$capturedBlockEntity(final BlockPos pos, final LevelChunk.EntityCreationType creationType, final CallbackInfoReturnable<BlockEntity> cir) {
        // CraftBukkit start
        BlockEntity blockEntity = this.level.capturedBlockEntities.get(pos);
        if (blockEntity != null) {
            cir.setReturnValue(blockEntity);
        }
        // CraftBukkit end
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void paper$removePendingBlockEntity(final BlockPos pos, final CallbackInfo ci) {
        if (this.isInLevel()) {
            // CraftBukkit start - SPIGOT-5561: Also remove from pending map
            final java.util.Map<BlockPos, net.minecraft.nbt.CompoundTag> pendingBlockEntities = ((ChunkAccessAccessorMixin) (Object) this).fission$pendingBlockEntities();
            if (!pendingBlockEntities.isEmpty()) {
                pendingBlockEntities.remove(pos);
            }
            // CraftBukkit end
        }
    }

    // CraftBukkit start
    public void loadCallback() {
        final LevelChunk self = (LevelChunk) (Object) this;
        final ServerLevel level = (ServerLevel) this.level;
        // Paper start
        this.loadedTicketLevel = true;
        // Paper end
        org.bukkit.Server server = level.getCraftServer();
        level.getChunkSource().addLoadedChunk(self); // Paper
        if (server != null) {
            /*
             * If it's a new world, the first few chunks are generated inside
             * the World constructor. We can't reliably alter that, so we have
             * no way of creating a CraftWorld/CraftServer at that point.
             */
            org.bukkit.Chunk bukkitChunk = new org.bukkit.craftbukkit.CraftChunk(self);
            server.getPluginManager().callEvent(new org.bukkit.event.world.ChunkLoadEvent(bukkitChunk, this.needsDecoration));

            if (this.needsDecoration) {
                this.needsDecoration = false;
                java.util.Random random = new java.util.Random();
                random.setSeed(level.getSeed());
                long xRand = random.nextLong() / 2L * 2L + 1L;
                long zRand = random.nextLong() / 2L * 2L + 1L;
                random.setSeed((long)self.getPos().x() * xRand + (long)self.getPos().z() * zRand ^ level.getSeed());

                org.bukkit.World world = level.getWorld();
                if (world != null) {
                    level.populating = true;
                    try {
                        for (org.bukkit.generator.BlockPopulator populator : world.getPopulators()) {
                            populator.populate(world, random, bukkitChunk);
                        }
                    } finally {
                        level.populating = false;
                    }
                }
                server.getPluginManager().callEvent(new org.bukkit.event.world.ChunkPopulateEvent(bukkitChunk));
            }
        }
    }

    public void unloadCallback() {
        final LevelChunk self = (LevelChunk) (Object) this;
        final ServerLevel level = (ServerLevel) this.level;
        org.bukkit.Server server = level.getCraftServer();
        org.bukkit.Chunk bukkitChunk = new org.bukkit.craftbukkit.CraftChunk(self);
        org.bukkit.event.world.ChunkUnloadEvent unloadEvent = new org.bukkit.event.world.ChunkUnloadEvent(bukkitChunk, self.isUnsaved());
        server.getPluginManager().callEvent(unloadEvent);
        // note: saving can be prevented, but not forced if no saving is actually required
        this.mustNotSave = !unloadEvent.isSaveChunk();
        level.getChunkSource().removeLoadedChunk(self); // Paper
        // Paper start
        this.loadedTicketLevel = false;
        // Paper end
    }
    // isUnsaved() && !mustNotSave: see ChunkAccessMixin
    // CraftBukkit end
    // config: chunks.fixed-chunk-inhabited-time (getInhabitedTime override), unsupported-settings.ticking.block-entities (updateBlockEntityTicker)
}
