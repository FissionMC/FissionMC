package net.fissionmc.backend.mixin.world.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link Entity} (paper-server/patches/sources/net/minecraft/world/entity/Entity.java.patch).
 * Fields and statics are private here; the loader widens them to the modifiers declared in surface/members/entity.txt.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow private Level level;
    @Shadow private Entity.@Nullable RemovalReason removalReason;
    @Shadow @Final protected SynchedEntityData entityData;
    @Shadow private int id;
    @Shadow private EntityDimensions dimensions;
    @Shadow private int remainingFireTicks;
    @Shadow private float yRot;
    @Shadow private @Nullable Entity vehicle;
    @Shadow private Vec3 position;

    @Shadow protected abstract AABB makeBoundingBox();
    @Shadow protected abstract void handlePortal();
    @Shadow protected abstract void removePassenger(Entity passenger);
    @Shadow protected abstract void addAdditionalSaveData(ValueOutput output);

    // CraftBukkit start
    private static final int CURRENT_LEVEL = 2;

    private static boolean isLevelAtLeast(ValueInput input, int level) {
        return input.getIntOr("Bukkit.updateLevel", CURRENT_LEVEL) >= level;
    }

    // TODO(behaviour): Paper - Share random for entities (SHARED_RANDOM / RandomRandomSource replaces RandomSource.create() for Entity.random)
    private org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason spawnReason; // Paper - Entity#getEntitySpawnReason

    private volatile org.bukkit.craftbukkit.entity.@Nullable CraftEntity bukkitEntity; // Paper - Folia schedulers - volatile

    public org.bukkit.craftbukkit.entity.CraftEntity getBukkitEntity() {
        if (this.bukkitEntity == null) {
            // Paper start - Folia schedulers
            synchronized (this) {
                if (this.bukkitEntity == null) {
                    return this.bukkitEntity = org.bukkit.craftbukkit.entity.CraftEntity.getEntity(this.level.getCraftServer(), (Entity) (Object) this);
                }
            }
            // Paper end - Folia schedulers
        }
        return this.bukkitEntity;
    }

    // Paper start
    public org.bukkit.craftbukkit.entity.@Nullable CraftEntity getBukkitEntityRaw() {
        return this.bukkitEntity;
    }
    // Paper end

    private net.kyori.adventure.util.TriState visualFire = net.kyori.adventure.util.TriState.NOT_SET; // Paper - improve visual fire API // TODO(behaviour): setSharedFlagOnFire + save/load
    private boolean persist = true;
    private boolean visibleByDefault = true;
    private boolean valid;
    private boolean inWorld = false;
    private boolean generation;
    private int maxAirTicks = this.getDefaultMaxAirSupply(); // CraftBukkit - SPIGOT-6907: re-implement LivingEntity#setMaximumAir() // TODO(behaviour): getMaxAirSupply returns maxAirTicks
    private org.bukkit.projectiles.@Nullable ProjectileSource projectileSource; // For projectiles only // Paper - Refresh ProjectileSource for projectiles
    private boolean lastDamageCancelled; // SPIGOT-5339, SPIGOT-6252, SPIGOT-6777: Keep track if the event was canceled
    private boolean persistentInvisibility = false;
    private @Nullable BlockPos lastLavaContact;
    // Marks an entity, that it was removed by a plugin via Entity#remove
    // Main use case currently is for SPIGOT-7487, preventing dropping of leash when leash is removed
    private boolean pluginRemoved = false;
    private org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause removeEventCause;
    private int numCollisions = 0; // Paper - Cap entity collisions
    private boolean fromNetherPortal; // Paper - Add option to nerf pigmen from nether portals
    private boolean spawnedViaMobSpawner; // Paper - Yes this name is similar to above, upstream took the better one
    // Paper start
    private @Nullable Vec3 origin;
    private @Nullable UUID originWorld;
    // Paper end
    private boolean freezeLocked = false; // Paper - Freeze Tick Lock API
    private boolean fixedPose = false; // Paper - Expand Pose API
    // TODO(behaviour): Paper - entity despawn time limit (despawnTime set in constructor, checked in tick())
    private int totalEntityAge; // Paper - age-like counter for all entities // TODO(behaviour): incremented in baseTick, saved as Spigot.ticksLived
    private final io.papermc.paper.entity.activation.ActivationType activationType = io.papermc.paper.entity.activation.ActivationType.activationTypeFor((Entity) (Object) this); // Paper - EAR 2/tracking ranges
    // CraftBukkit end

    // Paper start
    public final AABB getBoundingBoxAt(double x, double y, double z) {
        return this.dimensions.makeBoundingBox(x, y, z);
    }
    // Paper end

    // CraftBukkit start - SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
    public int getDefaultMaxAirSupply() {
        return Entity.TOTAL_AIR_SUPPLY;
    }
    // CraftBukkit end

    public final void discard(org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) {
        this.remove(Entity.RemovalReason.DISCARDED, cause);
        // CraftBukkit end
    }

    // CraftBukkit start
    public void refreshEntityData(ServerPlayer to) {
        Entity self = (Entity) (Object) this;
        if (self.isRemoved()) return; // Paper - Don't send entity data for removed entities
        List<SynchedEntityData.DataValue<?>> values = this.entityData.packAll(); // Paper - Update EVERYTHING not just not default

        if (to.getBukkitEntity().canSee(this.getBukkitEntity())) { // Paper
            to.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(self.getId(), values));
        }
    }
    // CraftBukkit end
    // Paper start
    // This method should only be used if the data of an entity could have become desynced
    // due to interactions on the client.
    public void resendPossiblyDesyncedEntityData(ServerPlayer player) {
        Entity self = (Entity) (Object) this;
        if (player.getBukkitEntity().canSee(this.getBukkitEntity())) {
            ServerLevel level = (ServerLevel) self.level();
            net.minecraft.server.level.ChunkMap.TrackedEntity tracker = level == null ? null : level.getChunkSource().chunkMap.entityMap.get(self.getId());
            if (tracker == null) {
                return;
            }
            final ServerEntity serverEntity = tracker.serverEntity;
            final List<Packet<? super ClientGamePacketListener>> list = new ArrayList<>();
            serverEntity.sendPairingData(player, list::add);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBundlePacket(list));
        }
    }

    // This method allows you to specifically resend certain data accessors to the client
    public void resendPossiblyDesyncedDataValues(List<EntityDataAccessor<?>> accessors, ServerPlayer to) {
        if (!to.getBukkitEntity().canSee(this.getBukkitEntity())) {
            return;
        }

        final List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(accessors.size());
        for (final EntityDataAccessor<?> accessor : accessors) {
            final SynchedEntityData.DataItem<?> item = this.entityData.getItem(accessor);
            values.add(item.value());
        }

        to.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(this.id, values));
    }
    // Paper end

    // FissionMC: the cause travels in fission$pendingRemoveCause through vanilla remove(reason) (which subclasses override) to setRemoved(reason)
    public void remove(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause eventCause) {
        final org.bukkit.event.entity.EntityRemoveEvent.Cause prev = this.fission$pendingRemoveCause;
        this.fission$pendingRemoveCause = eventCause;
        try {
            ((Entity) (Object) this).remove(reason); // -> setRemoved(reason, eventCause)
        } finally {
            this.fission$pendingRemoveCause = prev;
        }
        // CraftBukkit end
    }

    // CraftBukkit start
    // TODO(behaviour): baseTick() only calls handlePortal() for ServerPlayer; other entities portal here (called from the level tick)
    public void postTick() {
        Entity self = (Entity) (Object) this;
        // No clean way to break out of ticking once the entity has been copied to a new world, so instead we move the portalling later in the tick cycle
        if (!(self instanceof ServerPlayer) && self.isAlive()) { // Paper - don't attempt to teleport dead entities
            this.handlePortal();
        }
    }
    // CraftBukkit end

    public void lavaIgnite(@Nullable BlockPos pos) {
        Entity self = (Entity) (Object) this;
        // Paper end - track lava contact
        if (!self.fireImmune()) {
            // CraftBukkit start - Fallen in lava TODO: this event spams!
            if (self instanceof net.minecraft.world.entity.LivingEntity && this.remainingFireTicks <= 0) {
                // not on fire yet
                org.bukkit.block.Block damager = pos == null ? null : org.bukkit.craftbukkit.block.CraftBlock.at(this.level, pos);
                org.bukkit.entity.Entity damagee = this.getBukkitEntity();
                org.bukkit.event.entity.EntityCombustEvent combustEvent = new org.bukkit.event.entity.EntityCombustByBlockEvent(damager, damagee, 15.0F);

                if (combustEvent.callEvent()) {
                    this.igniteForSeconds(combustEvent.getDuration(), false);
                }
            } else {
                // This will be called every single tick the entity is in lava, so don't throw an event
                this.igniteForSeconds(15.0F, false);
            }
            // CraftBukkit end
        }
    }

    public void lavaHurt(@Nullable BlockPos pos) {
        Entity self = (Entity) (Object) this;
        // Paper end - track lava contact
        if (!self.fireImmune()) {
            if (self.level() instanceof ServerLevel serverLevel
                && self.hurtServer(serverLevel, self.damageSources().lava().eventBlockDamager(this.level, pos), 4.0F) // CraftBukkit - we also don't throw an event unless the object in lava is living, to save on some event calls
                && this.shouldPlayLavaHurtSound()
                && !self.isSilent()) {
                serverLevel.playSound(
                    null, self.getX(), self.getY(), self.getZ(), net.minecraft.sounds.SoundEvents.GENERIC_BURN, self.getSoundSource(), 0.4F, 2.0F + self.getRandom().nextFloat() * 0.4F
                );
            }
        }
    }

    @Shadow protected abstract boolean shouldPlayLavaHurtSound();

    public final void igniteForSeconds(float numberOfSeconds, final boolean callEvent) {
        if (callEvent) {
            org.bukkit.event.entity.EntityCombustEvent event = new org.bukkit.event.entity.EntityCombustEvent(this.getBukkitEntity(), numberOfSeconds);
            if (!event.callEvent()) {
                return;
            }

            numberOfSeconds = event.getDuration();
        }
        // CraftBukkit end
        ((Entity) (Object) this).igniteForTicks(net.minecraft.util.Mth.floor(numberOfSeconds * 20.0F));
    }

    // Paper - detailed watchdog information
    private final Object posLock = new Object(); // Paper - log detailed entity tick information // TODO(behaviour): synchronized around position/deltaMovement writes and move()

    public void push(final double xa, final double ya, final double za, @Nullable Entity pushingEntity) {
        Entity self = (Entity) (Object) this;
        // Paper end - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
        if (Double.isFinite(xa) && Double.isFinite(ya) && Double.isFinite(za)) {
            // Paper start - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
            org.bukkit.util.Vector delta = new org.bukkit.util.Vector(xa, ya, za);
            if (pushingEntity != null) {
                io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent event = new io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent(this.getBukkitEntity(), io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.PUSH, pushingEntity.getBukkitEntity(), delta);
                if (!event.callEvent()) {
                    return;
                }
                delta = event.getKnockback();
            }
            self.setDeltaMovement(self.getDeltaMovement().add(delta.getX(), delta.getY(), delta.getZ()));
            // Paper end - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
            self.needsSync = true;
        }
    }

    // TODO(behaviour): vanilla isPushable() should delegate to isCollidable(false); subclasses override isCollidable
    public boolean isCollidable(boolean ignoreClimbing) {
        // Paper end - Climbing should not bypass cramming gamerule
        return false;
    }

    // CraftBukkit start - collidable API
    public boolean canCollideWithBukkit(Entity entity) {
        return ((Entity) (Object) this).isPushable();
    }
    // CraftBukkit end

    // TODO(behaviour): vanilla saveAsPassenger(output) should delegate here (persist flag)
    public boolean saveAsPassenger(final ValueOutput output, final boolean includeAll, final boolean includeNonSaveable, final boolean forceSerialization) {
        // Paper end - Raw entity serialization API
        // CraftBukkit end
        if (this.removalReason != null && !this.removalReason.shouldSave() && !forceSerialization) { // Paper - Raw entity serialization API
            return false;
        }

        String id = this.getEncodeId(includeNonSaveable); // Paper - Raw entity serialization API
        if ((!this.persist && !forceSerialization) || id == null) { // CraftBukkit - persist flag // Paper - Raw entity serialization API
            return false;
        }

        output.putString("id", id);
        this.saveWithoutId(output, includeAll, includeNonSaveable, forceSerialization); // CraftBukkit - pass on includeAll // Paper - Raw entity serialization API
        return true;
    }

    // FissionMC: flags of the in-progress saveWithoutId(output, includeAll, includeNonSaveable, forceSerialization) call
    @org.spongepowered.asm.mixin.Unique private boolean fission$saveIncludeAll = true;
    @org.spongepowered.asm.mixin.Unique private boolean fission$saveIncludeNonSaveable = false;
    @org.spongepowered.asm.mixin.Unique private boolean fission$saveForceSerialization = false;

    // TODO(behaviour): the Paper/CraftBukkit save hunks inside vanilla saveWithoutId(output) (selective Pos/UUID/world, Bukkit.* and Paper.* keys,
    // passengers via the 4-arg saveAsPassenger, addAdditionalSaveData(output, includeAll), bukkit values) must read the fission$save* flags
    public void saveWithoutId(final ValueOutput output, final boolean includeAll, final boolean includeNonSaveable, final boolean forceSerialization) {
        final boolean prevIncludeAll = this.fission$saveIncludeAll;
        final boolean prevIncludeNonSaveable = this.fission$saveIncludeNonSaveable;
        final boolean prevForce = this.fission$saveForceSerialization;
        this.fission$saveIncludeAll = includeAll;
        this.fission$saveIncludeNonSaveable = includeNonSaveable;
        this.fission$saveForceSerialization = forceSerialization;
        try {
            ((Entity) (Object) this).saveWithoutId(output);
        } finally {
            this.fission$saveIncludeAll = prevIncludeAll;
            this.fission$saveIncludeNonSaveable = prevIncludeNonSaveable;
            this.fission$saveForceSerialization = prevForce;
        }
    }

    public final @Nullable String getEncodeId(final boolean includeNonSaveable) {
        Entity self = (Entity) (Object) this;
        if (!includeNonSaveable && !self.getType().canSerialize()) {
            // Paper end - Raw entity serialization API
            return null;
        }

        return self.typeHolder().unwrapKey().orElseThrow(() -> new IllegalStateException("Unregistered entity type")).identifier().toString();
    }

    // CraftBukkit start - allow excluding certain data when saving
    protected void addAdditionalSaveData(ValueOutput output, boolean includeAll) {
        this.addAdditionalSaveData(output);
    }
    // CraftBukkit end

    // TODO(behaviour): vanilla spawnAtLocation(level, stack, offset) should delegate here (death drop capture, EntityDropItemEvent)
    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemStack itemStack, final Vec3 offset, java.util.function.@Nullable Consumer<? super ItemEntity> delayedAddConsumer) {
        Entity self = (Entity) (Object) this;
        // Paper end - Restore vanilla drops behavior
        if (itemStack.isEmpty()) {
            return null;
        }
        // CraftBukkit start - Capture drops for death event
        if (self instanceof LivingEntity livingEntity && livingEntity.deathDropItems != null) {
            // Paper start - Restore vanilla drops behavior
            livingEntity.deathDropItems.add(new net.fissionmc.craft.compat.DefaultDrop(itemStack, dropStack -> {
                ItemEntity dropEntity = new ItemEntity(this.level, self.getX() + offset.x, self.getY() + offset.y, self.getZ() + offset.z, dropStack); // stack is copied before consumer
                dropEntity.setDefaultPickUpDelay();
                this.level.addFreshEntity(dropEntity);
                if (delayedAddConsumer != null) delayedAddConsumer.accept(dropEntity);
            }));
            // Paper end - Restore vanilla drops behavior
            return null;
        }
        // CraftBukkit end
        ItemEntity entity = new ItemEntity(level, self.getX() + offset.x, self.getY() + offset.y, self.getZ() + offset.z, itemStack);
        entity.setDefaultPickUpDelay(); // Paper - diff on change (in dropConsumer)
        // Paper start - Call EntityDropItemEvent
        return this.spawnAtLocation(level, entity);
    }

    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemEntity entity) {
        // Paper end - Call EntityDropItemEvent
        // CraftBukkit start
        org.bukkit.event.entity.EntityDropItemEvent event = new org.bukkit.event.entity.EntityDropItemEvent(this.getBukkitEntity(), (org.bukkit.entity.Item) entity.getBukkitEntity());
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return null;
        }
        // CraftBukkit end
        level.addFreshEntity(entity);
        return entity;
    }

    public boolean shearOffAllLeashConnections(final @Nullable Player player, final @Nullable InteractionHand hand) {
        Entity self = (Entity) (Object) this;
        // Paper end - EntityUnleashEvent - overload
        boolean dropped = this.dropAllLeashConnections(player, hand); // Paper - EntityUnleashEvent - overload
        if (dropped && self.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, self.blockPosition(), net.minecraft.sounds.SoundEvents.SHEARS_SNIP, player != null ? player.getSoundSource() : self.getSoundSource());
        }

        return dropped;
    }

    public boolean dropAllLeashConnections(final @Nullable Player player, final @Nullable InteractionHand hand) {
        Entity self = (Entity) (Object) this;
        // Paper end - EntityUnleashEvent - overload
        List<Leashable> leashables = Leashable.leashableLeashedTo(self);
        boolean dropped = false; // Paper - EntityUnleashEvent - compute flag later, events might prevent unleashing all connected leashables.
        if (self instanceof Leashable leashableThis && leashableThis.isLeashed()) {
            // Paper start - EntityUnleashEvent
            dropped |= org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent(
                self,
                player,
                hand,
                true,
                true
            );
            // Paper end - EntityUnleashEvent
        }

        for (Leashable leashable : leashables) {
            // Paper start - EntityUnleashEvent
            dropped |= org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent( // Update flag here, if any entity was unleashed, set to true.
                leashable,
                player,
                hand,
                true,
                true
            );
            // Paper end - EntityUnleashEvent
        }

        if (dropped) {
            self.gameEvent(GameEvent.SHEAR, player);
        }

        return dropped;
    }

    // FissionMC: suppressCancellation travels in fission$suppressDismountCancellation through vanilla removeVehicle()/stopRiding()
    // (which subclasses override) to the removePassenger call in removeVehicle()
    public void removeVehicle(boolean suppressCancellation) {
        final boolean prev = this.fission$suppressDismountCancellation;
        this.fission$suppressDismountCancellation = suppressCancellation;
        try {
            ((Entity) (Object) this).removeVehicle();
        } finally {
            this.fission$suppressDismountCancellation = prev;
        }
        // Paper end - Force entity dismount during teleportation
    }

    public void stopRiding(boolean suppressCancellation) {
        final boolean prev = this.fission$suppressDismountCancellation;
        this.fission$suppressDismountCancellation = suppressCancellation;
        try {
            ((Entity) (Object) this).stopRiding();
        } finally {
            this.fission$suppressDismountCancellation = prev;
        }
        // Paper end - Force entity dismount during teleportation
    }

    // FissionMC: Paper changes vanilla removePassenger(Entity) to return boolean; this overload fires the events, then runs the vanilla body
    protected boolean removePassenger(final Entity passenger, final boolean suppressCancellation) {
        // Paper end - Force entity dismount during teleportation
        if (passenger.getVehicle() == (Object) this) {
            throw new IllegalStateException("Use x.stopRiding(y), not y.removePassenger(x)");
        }
        // CraftBukkit start
        org.bukkit.craftbukkit.entity.CraftEntity craft = (org.bukkit.craftbukkit.entity.CraftEntity) passenger.getBukkitEntity().getVehicle();
        Entity orig = craft == null ? null : craft.getHandle();
        if (this.getBukkitEntity() instanceof org.bukkit.entity.Vehicle vehicle && passenger instanceof LivingEntity exited) {
            org.bukkit.event.vehicle.VehicleExitEvent event = new org.bukkit.event.vehicle.VehicleExitEvent(
                    vehicle, exited.getBukkitEntity(), !suppressCancellation // Paper - Force entity dismount during teleportation
            );
            // Suppress during worldgen
            if (this.valid) {
                org.bukkit.Bukkit.getPluginManager().callEvent(event);
            }
            org.bukkit.craftbukkit.entity.CraftEntity craftn = (org.bukkit.craftbukkit.entity.CraftEntity) passenger.getBukkitEntity().getVehicle();
            Entity n = craftn == null ? null : craftn.getHandle();
            if (event.isCancelled() || n != orig) {
                return false;
            }
        }

        org.bukkit.event.entity.EntityDismountEvent event = new org.bukkit.event.entity.EntityDismountEvent(passenger.getBukkitEntity(), this.getBukkitEntity(), !suppressCancellation); // Paper - Force entity dismount during teleportation
        // Suppress during worldgen
        if (this.valid) {
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
        }
        if (event.isCancelled()) {
            return false;
        }
        // CraftBukkit end
        this.removePassenger(passenger);
        return true; // CraftBukkit
    }

    // Paper start
    public boolean killedEntityPreEvent(final ServerLevel level, final LivingEntity entity, final DamageSource source) {
        return true;
    }
    // Paper end

    // TODO(behaviour): vanilla teleportTo(level, x, y, z, relatives, yRot, xRot, resetCamera) should delegate here with TeleportCause.UNKNOWN
    public boolean teleportTo(
        final ServerLevel level,
        final double x,
        final double y,
        final double z,
        final Set<Relative> relatives,
        final float newYRot,
        final float newXRot,
        final boolean resetCamera,
        final org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause
    ) {
        // CraftBukkit end
        Entity newEntity = ((Entity) (Object) this).teleport(
            new TeleportTransition(level, new Vec3(x, y, z), Vec3.ZERO, newYRot, newXRot, relatives, TeleportTransition.DO_NOTHING).withCause(cause) // CraftBukkit
        );
        return newEntity != null;
    }

    private static boolean checkPosition(Entity entity, double newX, double newY, double newZ) {
        if (Double.isFinite(newX) && Double.isFinite(newY) && Double.isFinite(newZ)) {
            return true;
        }

        String entityInfo;
        try {
            entityInfo = entity.toString();
        } catch (Exception ex) {
            entityInfo = "[Entity info unavailable] ";
        }
        LOGGER.error("New entity position is invalid! Tried to set invalid position ({},{},{}) for entity {} located at {}, entity info: {}", newX, newY, newZ, entity.getClass().getName(), entity.position(), entityInfo, new Throwable());
        return false;
    }

    // TODO(behaviour): vanilla setPosRaw(x, y, z)/setPos should run checkPosition and update the bounding box (Paper - Block invalid positions and bounding box)
    public final void setPosRaw(double x, double y, double z, boolean forceBoundingBoxUpdate) {
        Entity self = (Entity) (Object) this;
        if (!checkPosition(self, x, y, z)) {
            return;
        }
        // Paper end - Block invalid positions and bounding box
        self.setPosRaw(x, y, z);
        // Paper start - Block invalid positions and bounding box; don't allow desync of pos and AABB
        // hanging has its own special logic
        if (!(self instanceof net.minecraft.world.entity.decoration.HangingEntity) && (forceBoundingBoxUpdate || this.position.x != x || this.position.y != y || this.position.z != z)) {
            self.setBoundingBox(this.makeBoundingBox());
        }
        // Paper end - Block invalid positions and bounding box
    }

    // CraftBukkit start
    public float getBukkitYaw() {
        return this.yRot;
    }
    // CraftBukkit end

    public final void setRemoved(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) { // CraftBukkit - add Bukkit remove cause
        // FissionMC: the event, removeEventCause and the scheduler retirement happen in the injections into vanilla setRemoved(reason)
        final org.bukkit.event.entity.EntityRemoveEvent.Cause prev = this.fission$pendingRemoveCause;
        this.fission$pendingRemoveCause = cause;
        try {
            ((Entity) (Object) this).setRemoved(reason);
        } finally {
            this.fission$pendingRemoveCause = prev;
        }
    }

    // Paper start - Folia schedulers
    /**
     * Invoked only when the entity is truly removed from the server, never to be added to any world.
     */
    public final void retireScheduler() {
        // we need to force create the bukkit entity so that the scheduler can be retired...
        this.getBukkitEntity().taskScheduler.retire();
    }
    // Paper end - Folia schedulers

    // Paper start
    public boolean isTicking() {
        return ((ServerLevel) ((Entity) (Object) this).level()).isPositionEntityTicking(((Entity) (Object) this).blockPosition());
    }
    // Paper end

    // =====================================================================================================
    // Wave 2: behaviour hunks of Entity.java.patch
    // =====================================================================================================

    @Shadow @Final @org.spongepowered.asm.mixin.Mutable private Set<String> tags;
    @Shadow protected boolean firstTick;
    @Shadow public boolean noPhysics;
    @Shadow private float xRot;
    @Shadow @Final private static EntityDataAccessor<Integer> DATA_AIR_SUPPLY_ID;
    @Shadow public net.minecraft.world.entity.@Nullable PortalProcessor portalProcess;
    @Shadow private java.util.Optional<BlockPos> mainSupportingBlockPos;
    @Shadow public abstract boolean isInLava();
    @Shadow public abstract void setYHeadRot(float yHeadRot);
    @Shadow public abstract Level level();

    @org.spongepowered.asm.mixin.Unique private org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause fission$pendingRemoveCause;
    @org.spongepowered.asm.mixin.Unique private boolean fission$suppressDismountCancellation;
    @org.spongepowered.asm.mixin.Unique private int fission$despawnTime = -1; // Paper - entity despawn time limit (private final int despawnTime)
    @org.spongepowered.asm.mixin.Unique private net.minecraft.commands.@Nullable CommandSource fission$commandSource;

    // Paper start - Share random for entities to make them more random
    private static net.minecraft.util.RandomSource SHARED_RANDOM = new net.fissionmc.backend.world.entity.RandomRandomSource();

    @org.spongepowered.asm.mixin.injection.Redirect(method = "<init>", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private net.minecraft.util.RandomSource fission$sharedRandom() {
        return SHARED_RANDOM; // Paper - Share random for entities to make them more random
    }
    // Paper end - Share random for entities to make them more random

    @org.spongepowered.asm.mixin.injection.Redirect(method = "<init>", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lcom/google/common/collect/Sets;newHashSet()Ljava/util/HashSet;"))
    private java.util.HashSet<String> fission$limitedTags() {
        return null; // replaced in fission$initTail (Paper - fully limit tag size - replace set impl)
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void fission$initTail(EntityType<?> type, Level level, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.tags = new io.papermc.paper.util.SizeLimitedSet<>(new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>(), Entity.MAX_ENTITY_TAG_COUNT); // Paper - fully limit tag size - replace set impl
        this.fission$despawnTime = level == null || type == net.minecraft.world.entity.EntityTypes.PLAYER ? -1 : level.paperConfig().entities.spawning.despawnTime.getOrDefault(type, io.papermc.paper.configuration.type.number.IntOr.Disabled.DISABLED).or(-1); // Paper - entity despawn time limit
    }

    // CraftBukkit start - add Bukkit remove cause
    @org.spongepowered.asm.mixin.injection.Redirect(method = "kill", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void fission$killCause(Entity self, Entity.RemovalReason reason) {
        this.remove(reason, org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "onBelowWorld", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;discard()V"))
    private void fission$outOfWorldCause(Entity self) {
        this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.OUT_OF_WORLD); // CraftBukkit - add Bukkit remove cause
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setRemoved", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fission$setRemovedHead(Entity.RemovalReason reason, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci,
                                        @com.llamalad7.mixinextras.sugar.Share("alreadyRemoved") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef alreadyRemoved) {
        final org.bukkit.event.entity.EntityRemoveEvent.Cause cause = this.fission$pendingRemoveCause;
        this.fission$pendingRemoveCause = null;
        org.bukkit.craftbukkit.event.CraftEventFactory.callEntityRemoveEvent((Entity) (Object) this, cause); // CraftBukkit
        alreadyRemoved.set(this.removalReason != null); // Paper - Folia schedulers
        if (this.removalReason == null) {
            this.removeEventCause = cause; // Paper
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setRemoved", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fission$setRemovedTail(Entity.RemovalReason reason, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci,
                                        @com.llamalad7.mixinextras.sugar.Share("alreadyRemoved") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef alreadyRemoved) {
        // Paper start - Folia schedulers
        if (!((Object) this instanceof ServerPlayer) && reason != Entity.RemovalReason.CHANGED_DIMENSION && !alreadyRemoved.get()) {
            // Players need to be special cased, because they are regularly removed from the world
            this.retireScheduler();
        }
        // Paper end - Folia schedulers
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "unsetRemoved", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fission$unsetRemoved(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Paper start
        this.removeEventCause = null;
        this.pluginRemoved = false;
        // Paper end
    }
    // CraftBukkit end

    @org.spongepowered.asm.mixin.injection.Inject(method = "setPose", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$setPose(net.minecraft.world.entity.Pose pose, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (this.fixedPose) { ci.cancel(); return; } // Paper - Expand Pose API
        // CraftBukkit start
        if (pose == ((Entity) (Object) this).getPose()) {
            ci.cancel();
            return;
        }
        if (!this.generation) {
            this.level.getCraftServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPoseChangeEvent(this.getBukkitEntity(), org.bukkit.entity.Pose.values()[pose.ordinal()]));
        }
        // CraftBukkit end
    }

    // CraftBukkit start - yaw was sometimes set to NaN, so we need to set it back to 0
    @org.spongepowered.asm.mixin.injection.Inject(method = "setRot", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$setRot(float yRot, float xRot, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        final Entity self = (Entity) (Object) this;
        final float origY = yRot, origX = xRot;
        if (Float.isNaN(yRot)) {
            yRot = 0;
        }

        if (yRot == Float.POSITIVE_INFINITY || yRot == Float.NEGATIVE_INFINITY) {
            if (self instanceof ServerPlayer) {
                this.level.getCraftServer().getLogger().warning(self.getScoreboardName() + " was caught trying to crash the server with an invalid yaw");
                ((org.bukkit.craftbukkit.entity.CraftPlayer) this.getBukkitEntity()).kickPlayer("Infinite yaw (Hacking?)");
            }
            yRot = 0;
        }

        // pitch was sometimes set to NaN, so we need to set it back to 0
        if (Float.isNaN(xRot)) {
            xRot = 0;
        }

        if (xRot == Float.POSITIVE_INFINITY || xRot == Float.NEGATIVE_INFINITY) {
            if (self instanceof ServerPlayer) {
                this.level.getCraftServer().getLogger().warning(self.getScoreboardName() + " was caught trying to crash the server with an invalid pitch");
                ((org.bukkit.craftbukkit.entity.CraftPlayer) this.getBukkitEntity()).kickPlayer("Infinite pitch (Hacking?)");
            }
            xRot = 0;
        }
        // CraftBukkit end
        if (Float.compare(origY, yRot) != 0 || Float.compare(origX, xRot) != 0) {
            self.setYRot(yRot % 360.0F);
            self.setXRot(xRot % 360.0F);
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setPos(DDD)V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$setPos(double x, double y, double z, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.setPosRaw(x, y, z, true); // Paper - Block invalid positions and bounding box; force update
        // this.setBoundingBox(this.makeBoundingBox()); // Paper - Block invalid positions and bounding box; move into setPosRaw
        ci.cancel();
    }

    // Paper start - Block invalid positions and bounding box
    @org.spongepowered.asm.mixin.injection.Inject(method = "setPosRaw(DDD)V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$setPosRawHead(double x, double y, double z, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci,
                                       @com.llamalad7.mixinextras.sugar.Share("posChanged") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef posChanged) {
        if (!checkPosition((Entity) (Object) this, x, y, z)) {
            ci.cancel();
            return;
        }
        posChanged.set(this.position.x != x || this.position.y != y || this.position.z != z);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setPosRaw(DDD)V", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fission$setPosRawTail(double x, double y, double z, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci,
                                       @com.llamalad7.mixinextras.sugar.Share("posChanged") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef posChanged) {
        // Paper start - Fix MC-44654
        if (posChanged.get() && !((Entity) (Object) this).getType().hasUpdateInterval()) {
            ((Entity) (Object) this).needsSync = true;
        }
        // Paper end - Fix MC-44654
    }
    // Paper end - Block invalid positions and bounding box

    @org.spongepowered.asm.mixin.injection.Inject(method = "tick", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$despawnTimeLimit(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Paper start - entity despawn time limit
        if (this.fission$despawnTime >= 0 && this.totalEntityAge >= this.fission$despawnTime) {
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
            ci.cancel();
        }
        // Paper end - entity despawn time limit
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "commonTick", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fission$totalEntityAge(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.totalEntityAge++; // Paper - age-like counter for all entities
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "baseTick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;computeSpeed()V"))
    private void fission$initialPersistentAnger(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (this.firstTick && (Object) this instanceof net.minecraft.world.entity.NeutralMob neutralMob) neutralMob.tickInitialPersistentAnger(this.level); // Paper - Prevent entity loading causing async lookups
    }

    // TODO(behaviour): CraftBukkit moves handlePortal() for non-players from baseTick into postTick(), which Paper's ServerLevel#tickNonPassenger
    // calls. That ServerLevel hunk belongs to the server area; until it is ported, baseTick keeps calling handlePortal() for all entities.

    @org.spongepowered.asm.mixin.injection.Inject(method = "baseTick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;checkBelowWorld()V"))
    private void fission$clearLavaContact(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // CraftBukkit start
        if (!this.isInLava()) {
            this.lastLavaContact = null;
        }
        // CraftBukkit end
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setSharedFlagOnFire", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$visualFire(boolean value, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.setSharedFlag(0, this.visualFire.toBooleanOrElse(value)); // Paper - improve visual fire API (FLAG_ONFIRE = 0)
        ci.cancel();
    }

    @Shadow protected abstract void setSharedFlag(int flag, boolean value);
    @Shadow protected abstract void onBelowWorld();

    @org.spongepowered.asm.mixin.injection.Inject(method = "checkBelowWorld", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$checkBelowWorld(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        ci.cancel();
        final Entity self = (Entity) (Object) this;
        if (!this.level.getWorld().isVoidDamageEnabled()) return; // Paper - check if void damage is enabled on the world
        // Paper start - Configurable nether ceiling damage
        if (self.getY() < (this.level.getMinY() + this.level.getWorld().getVoidDamageMinBuildHeightOffset()) || (this.level.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER // Paper - use configured min build height offset
            && this.level.paperConfig().environment.netherCeilingVoidDamageHeight.test(v -> self.getY() >= v)
            && (!(self instanceof Player player) || !player.getAbilities().invulnerable))) {
            // Paper end - Configurable nether ceiling damage
            this.onBelowWorld();
        }
    }

    // Paper start - track lava contact
    @org.spongepowered.asm.mixin.injection.Inject(method = "lavaIgnite()V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$lavaIgnite(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.lavaIgnite(this.lastLavaContact); // fallback for minecarts if defined
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "lavaHurt()V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$lavaHurt(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.lavaHurt(this.lastLavaContact); // fallback for minecarts if defined
        ci.cancel();
    }
    // Paper end - track lava contact

    @org.spongepowered.asm.mixin.injection.Inject(method = "igniteForSeconds(F)V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$igniteForSeconds(float numberOfSeconds, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // CraftBukkit start
        this.igniteForSeconds(numberOfSeconds, true);
        ci.cancel();
    }

    // Paper start - Expose pre-collision velocity
    @org.spongepowered.asm.mixin.injection.Inject(method = "move", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fission$moveHead(net.minecraft.world.entity.MoverType moverType, Vec3 delta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci,
                                  @com.llamalad7.mixinextras.sugar.Share("originalMovement") com.llamalad7.mixinextras.sugar.ref.LocalRef<Vec3> originalMovement) {
        originalMovement.set(delta); // Paper - Expose pre-collision velocity
        // TODO(behaviour): Paper - detailed watchdog information (TickThread.ensureTickThread, posLock/moveVector/moveStart*) not ported
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "move", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getMovementEmission()Lnet/minecraft/world/entity/Entity$MovementEmission;"))
    private void fission$vehicleBlockCollision(net.minecraft.world.entity.MoverType moverType, Vec3 deltaArg, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci,
                                               @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Vec3 delta,
                                               @com.llamalad7.mixinextras.sugar.Local(index = 4) Vec3 movement,
                                               @com.llamalad7.mixinextras.sugar.Share("originalMovement") com.llamalad7.mixinextras.sugar.ref.LocalRef<Vec3> originalMovement) {
        final Entity self = (Entity) (Object) this;
        // CraftBukkit start
        if (self.horizontalCollision && this.getBukkitEntity() instanceof org.bukkit.entity.Vehicle) {
            org.bukkit.entity.Vehicle vehicle = (org.bukkit.entity.Vehicle) this.getBukkitEntity();
            org.bukkit.block.Block block = this.level.getWorld().getBlockAt(net.minecraft.util.Mth.floor(self.getX()), net.minecraft.util.Mth.floor(self.getY()), net.minecraft.util.Mth.floor(self.getZ()));

            if (delta.x > movement.x) {
                block = block.getRelative(org.bukkit.block.BlockFace.EAST);
            } else if (delta.x < movement.x) {
                block = block.getRelative(org.bukkit.block.BlockFace.WEST);
            } else if (delta.z > movement.z) {
                block = block.getRelative(org.bukkit.block.BlockFace.SOUTH);
            } else if (delta.z < movement.z) {
                block = block.getRelative(org.bukkit.block.BlockFace.NORTH);
            }

            if (!block.getType().isAir()) {
                org.bukkit.event.vehicle.VehicleBlockCollisionEvent event = new org.bukkit.event.vehicle.VehicleBlockCollisionEvent(vehicle, block, org.bukkit.craftbukkit.util.CraftVector.toBukkit(originalMovement.get())); // Paper - Expose pre-collision velocity
                event.callEvent();
            }
        }
        // CraftBukkit end
    }
    // Paper end - Expose pre-collision velocity

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "getOnPos(F)Lnet/minecraft/core/BlockPos;", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Ljava/util/Optional;isPresent()Z"))
    private boolean fission$getOnPosNoLoad(boolean present) {
        return present && this.level().getChunkIfLoadedImmediately(this.mainSupportingBlockPos.get()) != null; // Paper - ensure no loads
    }

    // Paper start - Add EntityLandEvent
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "checkFallDamage", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;D)V"))
    private void fission$entityLand(net.minecraft.world.level.block.Block block, Level level, net.minecraft.world.level.block.state.BlockState state, BlockPos pos, Entity entity, double fallDistance,
                                    com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original,
                                    @com.llamalad7.mixinextras.sugar.Share("landCancelled") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef landCancelled) {
        double distance = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityLandEvent(level, pos, entity, fallDistance);
        if (distance > 0) {
            original.call(block, level, state, pos, entity, distance);
        } else {
            landCancelled.set(true);
        }
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "checkFallDamage", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V"))
    private boolean fission$entityLandGameEvent(Level level, net.minecraft.core.Holder<GameEvent> event, Vec3 pos, GameEvent.Context context,
                                                @com.llamalad7.mixinextras.sugar.Share("landCancelled") com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef landCancelled) {
        return !landCancelled.get();
    }
    // Paper end - Add EntityLandEvent

    @org.spongepowered.asm.mixin.injection.Inject(method = "absSnapRotationTo", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fission$absSnapRotationHeadRot(float yRot, float xRot, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.setYHeadRot(yRot); // Paper - Update head rotation
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "absSnapTo(DDD)V", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fission$absSnapToLoadChunk(double x, double y, double z, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        final Entity self = (Entity) (Object) this;
        if (this.valid) this.level.getChunk((int) Math.floor(self.getX()) >> 4, (int) Math.floor(self.getZ()) >> 4); // CraftBukkit
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "snapTo(DDDFF)V", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void fission$snapToHeadRot(double x, double y, double z, float yRot, float xRot, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.setYHeadRot(yRot); // Paper - Update head rotation
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$pushEntity(Entity entity, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        final Entity self = (Entity) (Object) this;
        if (!self.isPassengerOfSameVehicle(entity) && !entity.noPhysics && !this.noPhysics) {
            if (this.level.paperConfig().collisions.onlyPlayersCollide && !(entity instanceof ServerPlayer || self instanceof ServerPlayer)) { ci.cancel(); return; } // Paper - Collision option for requiring a player participant
            // Paper start - Add EntityCollideWithEntity Event
            if (io.papermc.paper.event.entity.EntityCollideWithEntityEvent.getHandlerList().getRegisteredListeners().length != 0 &&
                !new io.papermc.paper.event.entity.EntityCollideWithEntityEvent(this.getBukkitEntity(), entity.getBukkitEntity()).callEvent()) { ci.cancel(); return; }
            // Paper end - Add EntityCollideWithEntity Event
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isPushable", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$isPushable(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        // Paper start - Climbing should not bypass cramming gamerule
        cir.setReturnValue(this.isCollidable(false));
    }

    // CraftBukkit start - allow excluding certain data when saving
    @org.spongepowered.asm.mixin.injection.Inject(method = "saveAsPassenger(Lnet/minecraft/world/level/storage/ValueOutput;)Z", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$saveAsPassenger(ValueOutput output, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        // Paper start - Raw entity serialization API
        cir.setReturnValue(this.saveAsPassenger(output, true, false, false));
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fission$saveNaNRotation(ValueOutput output, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // CraftBukkit start - Checking for NaN pitch/yaw and resetting to zero
        // TODO: make sure this is the best way to address this.
        if (Float.isNaN(this.yRot)) {
            this.yRot = 0;
        }

        if (Float.isNaN(this.xRot)) {
            this.xRot = 0;
        }
        // CraftBukkit end
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/ValueOutput;store(Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private boolean fission$selectiveSave(ValueOutput output, String key, com.mojang.serialization.Codec<?> codec, Object value) {
        // CraftBukkit - selectively save position, uuid and world
        return this.fission$saveIncludeAll || !("Pos".equals(key) || "UUID".equals(key));
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/ValueOutput;putBoolean(Ljava/lang/String;Z)V"))
    private boolean fission$skipVanillaVisualFire(ValueOutput output, String key, boolean value) {
        return !"HasVisualFire".equals(key); // Paper - improve visual fire API (written in fission$saveBukkitData)
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueOutput;)V"))
    private void fission$saveBukkitData(Entity self, ValueOutput output) {
        final boolean includeAll = this.fission$saveIncludeAll;
        // CraftBukkit start - selectively save uuid and world
        if (includeAll) {
            // PAIL: Check above UUID reads 1.8 properly, ie: UUIDMost / UUIDLeast
            output.putLong("WorldUUIDLeast", this.level.getWorld().getUID().getLeastSignificantBits());
            output.putLong("WorldUUIDMost", this.level.getWorld().getUID().getMostSignificantBits());
        }
        output.putInt("Bukkit.updateLevel", CURRENT_LEVEL);
        if (!this.persist) {
            output.putBoolean("Bukkit.persist", this.persist);
        }
        if (!this.visibleByDefault) {
            output.putBoolean("Bukkit.visibleByDefault", this.visibleByDefault);
        }
        if (this.persistentInvisibility) {
            output.putBoolean("Bukkit.invisible", this.persistentInvisibility);
        }
        // SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
        if (this.maxAirTicks != this.getDefaultMaxAirSupply()) {
            output.putInt("Bukkit.MaxAirSupply", self.getMaxAirSupply());
        }
        output.putInt("Spigot.ticksLived", this.totalEntityAge); // Paper
        // CraftBukkit end
        // Paper start - improve visual fire API
        if (this.visualFire.equals(net.kyori.adventure.util.TriState.TRUE)) {
            output.putBoolean("HasVisualFire", true);
        }
        if (this.visualFire != net.kyori.adventure.util.TriState.NOT_SET) {
            output.putString("Paper.FireOverride", visualFire.name());
        }
        // Paper end

        this.addAdditionalSaveData(output, includeAll); // CraftBukkit - pass on includeAll

        // CraftBukkit start - stores eventually existing bukkit values
        if (this.bukkitEntity != null) {
            this.bukkitEntity.storeBukkitValues(output);
        }
        // CraftBukkit end
        // Paper start
        if (this.origin != null) {
            UUID originWorld = this.originWorld != null ? this.originWorld : (this.level != null ? this.level.getWorld().getUID() : null);
            if (originWorld != null) {
                output.store("Paper.OriginWorld", net.minecraft.core.UUIDUtil.CODEC, originWorld);
            }
            output.store("Paper.Origin", Vec3.CODEC, this.origin);
        }
        if (this.spawnReason != null) {
            output.putString("Paper.SpawnReason", this.spawnReason.name());
        }
        // Save entity's from mob spawner status
        if (this.spawnedViaMobSpawner) {
            output.putBoolean("Paper.FromMobSpawner", true);
        }
        if (this.fromNetherPortal) {
            output.putBoolean("Paper.FromNetherPortal", true);
        }
        if (this.freezeLocked) {
            output.putBoolean("Paper.FreezeLock", true);
        }
        // Paper end
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;)V", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;saveAsPassenger(Lnet/minecraft/world/level/storage/ValueOutput;)Z"))
    private boolean fission$savePassenger(Entity passenger, ValueOutput passengerOutput) {
        return passenger.saveAsPassenger(passengerOutput, this.fission$saveIncludeAll, this.fission$saveIncludeNonSaveable, this.fission$saveForceSerialization); // CraftBukkit - pass on includeAll // Paper - Raw entity serialization API
    }
    // CraftBukkit end

    @org.spongepowered.asm.mixin.injection.Inject(method = "load", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueInput;)V"))
    private void fission$loadVisualFire(ValueInput input, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Paper start - improve visual fire API
        input.getString("Paper.FireOverride").ifPresentOrElse(
            override -> {
                try {
                    this.visualFire = net.kyori.adventure.util.TriState.valueOf(override);
                } catch (final Exception ignored) {
                    LOGGER.error("Unknown fire override {} for {}", override, this);
                }
            },
            () -> this.visualFire = input.read("HasVisualFire", com.mojang.serialization.Codec.BOOL)
                .map(net.kyori.adventure.util.TriState::byBoolean)
                .orElse(net.kyori.adventure.util.TriState.NOT_SET)
        );
        // Paper end
        // config: scoreboards.allow-non-player-entities-on-scoreboards (team lookup in load / getTeam) - default true = vanilla
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "load", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueInput;)V", shift = org.spongepowered.asm.mixin.injection.At.Shift.AFTER))
    private void fission$loadBukkitData(ValueInput input, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        final Entity self = (Entity) (Object) this;
        // CraftBukkit start
        this.totalEntityAge = input.getIntOr("Spigot.ticksLived", 0); // Paper
        this.persist = input.getBooleanOr("Bukkit.persist", true);
        this.visibleByDefault = input.getBooleanOr("Bukkit.visibleByDefault", true);
        // SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
        this.maxAirTicks = input.getIntOr("Bukkit.MaxAirSupply", this.maxAirTicks);
        // CraftBukkit end

        // CraftBukkit start
        // Paper - move world parsing/loading to PlayerList#placeNewPlayer
        this.getBukkitEntity().readBukkitValues(input);
        input.read("Bukkit.invisible", com.mojang.serialization.Codec.BOOL).ifPresent(bukkitInvisible -> {
            self.setInvisible(bukkitInvisible);
            this.persistentInvisibility = bukkitInvisible;
        });
        // CraftBukkit end

        // Paper start
        java.util.Optional<Vec3> originVec = input.read("Paper.Origin", Vec3.CODEC);
        if (originVec.isPresent()) {
            this.originWorld = input.read("Paper.OriginWorld", net.minecraft.core.UUIDUtil.CODEC)
                .orElse(this.level != null ? this.level.getWorld().getUID() : null);
            this.origin = originVec.get();
        }

        spawnedViaMobSpawner = input.getBooleanOr("Paper.FromMobSpawner", false); // Restore entity's from mob spawner status
        fromNetherPortal = input.getBooleanOr("Paper.FromNetherPortal", false);
        input.getString("Paper.SpawnReason").ifPresent(spawnReasonName -> {
            try {
                spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.valueOf(spawnReasonName);
            } catch (Exception ignored) {
                LOGGER.error("Unknown SpawnReason " + spawnReasonName + " for " + this);
            }
        });
        if (spawnReason == null) {
            if (spawnedViaMobSpawner) {
                spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER;
            } else if (self instanceof net.minecraft.world.entity.Mob && (self instanceof net.minecraft.world.entity.animal.Animal || self instanceof net.minecraft.world.entity.animal.fish.AbstractFish) && !((net.minecraft.world.entity.Mob) self).removeWhenFarAway(0.0)) {
                if (!input.getBooleanOr("PersistenceRequired", false)) {
                    spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL;
                }
            }
        }
        if (spawnReason == null) {
            spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT;
        }
        freezeLocked = input.getBooleanOr("Paper.FreezeLock", false);
        // Paper end
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/entity/item/ItemEntity;", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$spawnAtLocation(ServerLevel level, ItemStack itemStack, Vec3 offset, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<ItemEntity> cir) {
        // Paper start - Restore vanilla drops behavior
        cir.setReturnValue(this.spawnAtLocation(level, itemStack, offset, null));
    }

    // Leash events: Paper changes most of interact(), so the body is replaced (Paper - PlayerLeashEvent / EntityUnleashEvent / EntityLeashEvent)
    @org.spongepowered.asm.mixin.injection.Inject(method = "interact", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$interact(Player player, InteractionHand hand, Vec3 location, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.InteractionResult> cir) {
        cir.setReturnValue(this.fission$paperInteract(player, hand, location));
    }

    @org.spongepowered.asm.mixin.Unique
    private net.minecraft.world.InteractionResult fission$paperInteract(final Player player, final InteractionHand hand, final Vec3 location) {
        final Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide()
            && player.isSecondaryUseActive()
            && self instanceof Leashable leashable
            && leashable.canBeLeashed()
            && self.isAlive()
            && !(self instanceof LivingEntity le && le.isBaby())) {
            List<Leashable> mobsToLeash = Leashable.leashableInArea(self, l -> l.getLeashHolder() == player);
            if (!mobsToLeash.isEmpty()) {
                boolean anyLeashed = false;

                for (Leashable mob : mobsToLeash) {
                    if (mob.canHaveALeashAttachedTo(self)) {
                        // Paper start - PlayerLeashEvent
                        final org.bukkit.event.entity.PlayerLeashEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerLeashEntityEvent(
                            mob,
                            self,
                            player,
                            hand
                        );
                        if (event != null && event.isCancelled()) continue; // If the event was called and cancelled, skip this.
                        // Paper end - PlayerLeashEvent
                        mob.setLeashedTo(self, true);
                        anyLeashed = true;
                    }
                }

                if (anyLeashed) {
                    self.level().gameEvent(GameEvent.ENTITY_ACTION, self.blockPosition(), GameEvent.Context.of(player));
                    self.playSound(net.minecraft.sounds.SoundEvents.LEAD_TIED);
                    return net.minecraft.world.InteractionResult.SUCCESS_SERVER.withoutItem();
                }
            }
        }

        ItemStack heldItem = player.getItemInHand(hand);
        if (heldItem.is(net.minecraft.world.item.Items.SHEARS) && this.shearOffAllLeashConnections(player, hand)) { // Paper - PlayerUnleashEntityEvent - pass used hand
            heldItem.hurtAndBreak(1, player, hand);
            return net.minecraft.world.InteractionResult.SUCCESS;
        } else if (self instanceof net.minecraft.world.entity.Mob target
            && heldItem.is(net.minecraft.world.item.Items.SHEARS)
            && ((MobInvokerMixin) target).fission$canShearEquipment(player)
            && !player.isSecondaryUseActive()
            && ((MobInvokerMixin) target).fission$attemptToShearEquipment(player, hand, heldItem)) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        } else {
            if (self.isAlive() && self instanceof Leashable leashable) {
                if (leashable.getLeashHolder() == player) {
                    if (!self.level().isClientSide()) {
                        // Paper start - EntityUnleashEvent
                        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent(
                            leashable, player, hand, !player.hasInfiniteMaterials(), true
                        )) {
                            return net.minecraft.world.InteractionResult.PASS;
                        }
                        // Paper end - EntityUnleashEvent

                        self.gameEvent(GameEvent.ENTITY_INTERACT, player);
                        self.playSound(net.minecraft.sounds.SoundEvents.LEAD_UNTIED);
                    }

                    return net.minecraft.world.InteractionResult.SUCCESS.withoutItem();
                }

                ItemStack itemStack = player.getItemInHand(hand);
                if (itemStack.is(net.minecraft.world.item.Items.LEAD) && !(leashable.getLeashHolder() instanceof Player)) {
                    if (self.level().isClientSide()) {
                        return net.minecraft.world.InteractionResult.CONSUME;
                    }

                    if (leashable.canHaveALeashAttachedTo(player)) {
                        if (leashable.isLeashed()) {
                            // Paper start - EntityUnleashEvent
                            if (!org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent(
                                leashable, player, hand, true, true
                            )) {
                                return net.minecraft.world.InteractionResult.PASS;
                            }
                            // Paper end - EntityUnleashEvent
                            // leashable.dropLeash(); // Paper - EntityUnleashEvent - moved into handlePlayerUnleashEntityEvent
                        }

                        // Paper start - EntityLeashEvent
                        if (org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerLeashEntityEvent(self, player, player, hand).isCancelled()) {
                            ((ServerPlayer) player).connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket(self, leashable.getLeashHolder()));
                            return net.minecraft.world.InteractionResult.PASS;
                        }
                        // Paper end - EntityLeashEvent
                        leashable.setLeashedTo(player, true);
                        self.playSound(net.minecraft.sounds.SoundEvents.LEAD_TIED);
                        itemStack.shrink(1);
                        return net.minecraft.world.InteractionResult.SUCCESS_SERVER;
                    }
                }
            }

            return net.minecraft.world.InteractionResult.PASS;
        }
    }

    // Paper start - EntityUnleashEvent - overload
    @org.spongepowered.asm.mixin.injection.Inject(method = "shearOffAllLeashConnections(Lnet/minecraft/world/entity/player/Player;)Z", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$shearOffAll(@Nullable Player player, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.shearOffAllLeashConnections(player, null));
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "dropAllLeashConnections(Lnet/minecraft/world/entity/player/Player;)Z", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$dropAll(@Nullable Player player, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.dropAllLeashConnections(player, null));
    }
    // Paper end - EntityUnleashEvent - overload

    @org.spongepowered.asm.mixin.injection.Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$startRidingSameLevel(Entity entityToRide, boolean force, boolean sendEventAndTriggers, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (entityToRide.level() != this.level) { // Paper - Ensure entity passenger world matches ridden entity (bad plugins)
            cir.setReturnValue(false);
        }
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z"))
    private boolean fission$forceRideAll(boolean canSerialize, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true, ordinal = 0) boolean force) {
        return canSerialize || force; // SPIGOT-7947: Allow force riding all entities
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isPassenger()Z"), cancellable = true)
    private void fission$mountEvents(Entity entityToRide, boolean force, boolean sendEventAndTriggers, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        // CraftBukkit start
        if (entityToRide.getBukkitEntity() instanceof org.bukkit.entity.Vehicle vehicle && (Object) this instanceof LivingEntity entered) {
            org.bukkit.event.vehicle.VehicleEnterEvent event = new org.bukkit.event.vehicle.VehicleEnterEvent(vehicle, entered.getBukkitEntity());
            // Suppress during worldgen
            if (this.valid) {
                org.bukkit.Bukkit.getPluginManager().callEvent(event);
            }
            if (event.isCancelled()) {
                cir.setReturnValue(false);
                return;
            }
        }

        org.bukkit.event.entity.EntityMountEvent event = new org.bukkit.event.entity.EntityMountEvent(this.getBukkitEntity(), entityToRide.getBukkitEntity());
        // Suppress during worldgen
        if (this.valid) {
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
        }
        if (event.isCancelled()) {
            cir.setReturnValue(false);
        }
        // CraftBukkit end
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "removeVehicle()V", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;removePassenger(Lnet/minecraft/world/entity/Entity;)V"))
    private void fission$removePassengerEvents(Entity oldVehicle, Entity passenger, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (!((EntityMixin) (Object) oldVehicle).removePassenger(passenger, this.fission$suppressDismountCancellation)) this.vehicle = oldVehicle; // CraftBukkit // Paper - Force entity dismount during teleportation
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setSwimming", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$toggleSwim(boolean swimming, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // CraftBukkit start
        if (this.valid && ((Entity) (Object) this).isSwimming() != swimming && (Object) this instanceof LivingEntity living) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callToggleSwimEvent(living, swimming).isCancelled()) {
                ci.cancel();
            }
        }
        // CraftBukkit end
    }

    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "considersEntityAsAlly", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private boolean fission$alliesApi(boolean allied, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Entity other) {
        return allied || this.getBukkitEntity().considersEntityAsAlly0(other.getBukkitEntity()); // Paper - Allies API
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setInvisible", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$persistentInvisibility(boolean invisible, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // CraftBukkit - start
        if (this.persistentInvisibility) { // Prevent Minecraft from removing our invisibility flag
            ci.cancel();
        }
        // CraftBukkit - end
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "getMaxAirSupply", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$maxAirSupply(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.maxAirTicks); // CraftBukkit - SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "setAirSupply", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$airChange(int supply, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Paper start - EntityAirChangeEvent
        // Suppress during worldgen
        if (this.valid && org.bukkit.event.entity.EntityAirChangeEvent.getHandlerList().hasListeners() && ((Entity) (Object) this).getAirSupply() != supply) {
            final org.bukkit.event.entity.EntityAirChangeEvent event = new org.bukkit.event.entity.EntityAirChangeEvent(this.getBukkitEntity(), supply);
            if (!event.callEvent()) {
                ci.cancel();
                return;
            }

            if (event.getAmount() != supply) {
                this.entityData.set(DATA_AIR_SUPPLY_ID, event.getAmount());
                ci.cancel();
            }
        }
        // Paper end - EntityAirChangeEvent
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "clearFreeze", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$freezeLock(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (this.freezeLocked) ci.cancel(); // Paper - Freeze Tick Lock API
    }

    // replaced body (CraftBukkit - combust/hanging events, DamageSource with the bolt as damager)
    @org.spongepowered.asm.mixin.injection.Inject(method = "thunderHit", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$thunderHit(ServerLevel level, net.minecraft.world.entity.LightningBolt lightningBolt, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        ci.cancel();
        final Entity self = (Entity) (Object) this;
        self.setRemainingFireTicks(this.remainingFireTicks + 1);
        // CraftBukkit start
        final org.bukkit.entity.Entity thisBukkitEntity = this.getBukkitEntity();
        final org.bukkit.entity.Entity stormBukkitEntity = lightningBolt.getBukkitEntity();
        final org.bukkit.plugin.PluginManager pluginManager = org.bukkit.Bukkit.getPluginManager();
        // CraftBukkit end
        if (this.remainingFireTicks == 0) {
            // CraftBukkit start - Call a combust event when lightning strikes
            org.bukkit.event.entity.EntityCombustByEntityEvent entityCombustEvent = new org.bukkit.event.entity.EntityCombustByEntityEvent(stormBukkitEntity, thisBukkitEntity, 8.0F);
            pluginManager.callEvent(entityCombustEvent);
            if (!entityCombustEvent.isCancelled()) {
                this.igniteForSeconds(entityCombustEvent.getDuration(), false);
                // Paper start - fix EntityCombustEvent cancellation
            } else {
                self.setRemainingFireTicks(this.remainingFireTicks - 1);
                // Paper end - fix EntityCombustEvent cancellation
            }
            // CraftBukkit end
        }

        // CraftBukkit start
        final DamageSource damageSource = self.damageSources().lightningBolt().eventEntityDamager(lightningBolt);
        if (thisBukkitEntity instanceof org.bukkit.entity.Hanging) {
            org.bukkit.event.hanging.HangingBreakByEntityEvent hangingEvent = new org.bukkit.event.hanging.HangingBreakByEntityEvent((org.bukkit.entity.Hanging) thisBukkitEntity, stormBukkitEntity, new org.bukkit.craftbukkit.damage.CraftDamageSource(damageSource));
            if (!hangingEvent.callEvent()) {
                return;
            }
        }

        if (!self.hurtServer(level, damageSource, 5.0F)) { // Paper - fix DamageSource API
            return;
        }
        // CraftBukkit end
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "restoreFrom", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fission$forwardCraftEntity(Entity oldEntity, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Paper start - Forward CraftEntity in teleport command
        org.bukkit.craftbukkit.entity.CraftEntity bukkitEntity = ((EntityMixin) (Object) oldEntity).bukkitEntity;
        if (bukkitEntity != null) {
            bukkitEntity.setHandle((Entity) (Object) this);
            this.bukkitEntity = bukkitEntity;
        }
        // Paper end - Forward CraftEntity in teleport command
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "teleport")
    private @Nullable Entity fission$teleportEvents(TeleportTransition transition, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Entity> original) {
        final Entity self = (Entity) (Object) this;
        // Paper start - Fix item duplication and teleport issues
        if ((!self.isAlive() || !this.valid) && (transition.newLevel() != this.level)) {
            LOGGER.warn("Illegal Entity Teleport {} to {}:{}", this, transition.newLevel(), transition.position(), new Throwable());
            return null;
        }
        // Paper end - Fix item duplication and teleport issues
        if (self.level() instanceof ServerLevel && !self.isRemoved()) {
            // CraftBukkit start
            net.minecraft.world.entity.PositionMoveRotation absolutePosition = net.minecraft.world.entity.PositionMoveRotation.calculateAbsolute(net.minecraft.world.entity.PositionMoveRotation.of(self), net.minecraft.world.entity.PositionMoveRotation.of(transition), transition.relatives());
            Vec3 velocity = absolutePosition.deltaMovement(); // Paper
            org.bukkit.Location to = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(absolutePosition.position(), transition.newLevel(), absolutePosition.yRot(), absolutePosition.xRot());
            // Paper start - gateway-specific teleport event
            final org.bukkit.event.entity.EntityTeleportEvent teleEvent;
            if (this.portalProcess != null && this.portalProcess.isSamePortal(((net.minecraft.world.level.block.EndGatewayBlock) net.minecraft.world.level.block.Blocks.END_GATEWAY)) && this.level.getBlockEntity(this.portalProcess.getEntryPosition()) instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity theEndGatewayBlockEntity) {
                teleEvent = new com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent(this.getBukkitEntity(), this.getBukkitEntity().getLocation(), to, new org.bukkit.craftbukkit.block.CraftEndGateway(to.getWorld(), theEndGatewayBlockEntity, true));
                teleEvent.callEvent();
            } else {
                teleEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTeleportEvent(self, to);
            }
            // Paper end - gateway-specific teleport event
            if (teleEvent.isCancelled() || teleEvent.getTo() == null) {
                return null;
            }
            if (!to.equals(teleEvent.getTo())) {
                to = teleEvent.getTo();
                transition = transition.withChangedLocation(to).withVelocity(Vec3.ZERO).withRelatives(Set.of());
                // Paper start - Call EntityPortalExitEvent
                velocity = Vec3.ZERO;
            }
            if (this.portalProcess != null) { // if in a portal
                org.bukkit.entity.Entity bukkitEntity = this.getBukkitEntity();
                org.bukkit.util.Vector before = bukkitEntity.getVelocity();
                org.bukkit.util.Vector after = org.bukkit.craftbukkit.util.CraftVector.toBukkit(velocity);
                org.bukkit.event.entity.EntityPortalExitEvent event = new org.bukkit.event.entity.EntityPortalExitEvent(
                    bukkitEntity,
                    bukkitEntity.getLocation(), to.clone(),
                    before, after
                );
                event.callEvent();

                // Only change the target if actually needed, since we reset relative flags
                if (event.isCancelled() || !to.equals(event.getTo()) || !after.equals(event.getAfter())) {
                    if (event.isCancelled() || event.getTo() == null) {
                        org.bukkit.World toWorld = to.getWorld();
                        to = event.getFrom().clone();
                        to.setWorld(toWorld); // cancelling doesn't cancel the teleport just the position/velocity (old quirk)
                        velocity = org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getBefore());
                    } else {
                        to = event.getTo().clone();
                        velocity = org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getAfter());
                    }
                    transition = transition.withChangedLocation(to).withRelatives(Set.of()).withVelocity(velocity);
                }
            }
            if (self.isRemoved()) {
                return null;
            }
            // Paper end - Call EntityPortalExitEvent
            // CraftBukkit end
        }
        return original.call(transition);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "teleportCrossDimension", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;restoreFrom(Lnet/minecraft/world/entity/Entity;)V"))
    private void fission$teleportDropLeash(ServerLevel oldLevel, ServerLevel newLevel, TeleportTransition transition, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Entity> cir) {
        // Paper start - Fix item duplication and teleport issues
        if ((Object) this instanceof Leashable leashable) {
            leashable.dropLeash(); // Paper drop lead
        }
        // Paper end - Fix item duplication and teleport issues
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "teleportCrossDimension", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addDuringTeleport(Lnet/minecraft/world/entity/Entity;)V"))
    private boolean fission$addDuringTeleportIfInWorld(ServerLevel newLevel, Entity newEntity) {
        return this.inWorld; // CraftBukkit - Don't spawn the new entity if the current entity isn't spawned
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "calculatePassengerTransition", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fission$positionRider(TeleportTransition transition, Entity passenger, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<TeleportTransition> cir) {
        // Paper start - reposition entity as rider when teleporting via API call
        // This change is mostly required for plugins teleporting entities *every* tick with passengers, specifically player passengers.
        // The relative teleport of players, which base their location on the server -> client -> server teleport packet cycle, means player
        // positions can quickly be desynced from their rider position, leading to infinitely growing offsets from their vehicle.
        // The addition to the teleport transition allows existing callers to this method to not accidentally mutate the entity state.
        if (transition.passengerTeleportationMode() == net.fissionmc.craft.compat.PassengerTeleportationMode.POSITION_RIDER) ((Entity) (Object) this).positionRider(passenger);
        // Paper end - reposition entity as rider when teleporting via API call
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "removeAfterChangingDimensions", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Leashable;removeLeash()V"))
    private void fission$unleashOnDimensionChange(Leashable leashable, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (!leashable.isLeashed()) return; // Paper - only call if it is leashed
        // Paper start - Expand EntityUnleashEvent
        final org.bukkit.event.entity.EntityUnleashEvent event = new org.bukkit.event.entity.EntityUnleashEvent(this.getBukkitEntity(), org.bukkit.event.entity.EntityUnleashEvent.UnleashReason.UNKNOWN, false); // CraftBukkit
        event.callEvent();
        if (!event.isDropLeash()) {
            original.call(leashable);
        } else {
            leashable.dropLeash();
        }
        // Paper end - Expand EntityUnleashEvent
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "canTeleport", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void fission$canTeleport(Level from, Level to, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (!((Entity) (Object) this).isAlive() || !this.valid) cir.setReturnValue(false); // Paper - Fix item duplication and teleport issues
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "setBoundingBox", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), argsOnly = true)
    private AABB fission$blockInvalidBoundingBox(AABB bb) {
        // CraftBukkit start - block invalid bounding boxes
        double minX = bb.minX,
                minY = bb.minY,
                minZ = bb.minZ,
                maxX = bb.maxX,
                maxY = bb.maxY,
                maxZ = bb.maxZ;
        double len = bb.maxX - bb.minX;
        if (len < 0) maxX = minX;
        if (len > 64) maxX = minX + 64.0;

        len = bb.maxY - bb.minY;
        if (len < 0) maxY = minY;
        if (len > 64) maxY = minY + 64.0;

        len = bb.maxZ - bb.minZ;
        if (len < 0) maxZ = minZ;
        if (len > 64) maxZ = minZ + 64.0;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        // CraftBukkit end
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "stopSeenByPlayer", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fission$untrackEvent(ServerPlayer player, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Paper start - entity tracking events
        // Since this event cannot be cancelled, we should call it here to catch all "un-tracks"
        if (io.papermc.paper.event.player.PlayerUntrackEntityEvent.getHandlerList().getRegisteredListeners().length > 0) {
            new io.papermc.paper.event.player.PlayerUntrackEntityEvent(player.getBukkitEntity(), this.getBukkitEntity()).callEvent();
        }
        // Paper end - entity tracking events
    }

    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "createCommandSourceStackForNameResolution", at = @org.spongepowered.asm.mixin.injection.At(value = "FIELD", target = "Lnet/minecraft/commands/CommandSource;NULL:Lnet/minecraft/commands/CommandSource;"))
    private net.minecraft.commands.CommandSource fission$entityCommandSource(net.minecraft.commands.CommandSource original) {
        // CraftBukkit
        if (this.fission$commandSource == null) {
            this.fission$commandSource = new net.fissionmc.backend.world.entity.EntityCommandSource((Entity) (Object) this);
        }
        return this.fission$commandSource;
    }
}
