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

    // TODO(behaviour): vanilla remove(reason)/kill/discard()/onBelowWorld should route through the cause overloads; subclasses override remove(reason, cause)
    public void remove(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause eventCause) {
        this.setRemoved(reason, eventCause);
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

    // TODO(behaviour): vanilla removeVehicle()/stopRiding() should delegate to the boolean overloads; subclasses override stopRiding(boolean)
    public void removeVehicle(boolean suppressCancellation) {
        Entity self = (Entity) (Object) this;
        // Paper end - Force entity dismount during teleportation
        if (this.vehicle != null) {
            Entity oldVehicle = this.vehicle;
            this.vehicle = null;
            if (!((EntityMixin) (Object) oldVehicle).removePassenger(self, suppressCancellation)) this.vehicle = oldVehicle; // CraftBukkit // Paper - Force entity dismount during teleportation
            Entity.RemovalReason removalReason = self.getRemovalReason();
            if (removalReason == null || removalReason.shouldDestroy()) {
                self.level().gameEvent(self, GameEvent.ENTITY_DISMOUNT, oldVehicle.position());
            }
        }
    }

    public void stopRiding(boolean suppressCancellation) {
        this.removeVehicle(suppressCancellation);
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

    // TODO(behaviour): vanilla setRemoved(reason) should delegate here with a null cause (EntityAccess default in Paper)
    public final void setRemoved(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) { // CraftBukkit - add Bukkit remove cause
        Entity self = (Entity) (Object) this;
        org.bukkit.craftbukkit.event.CraftEventFactory.callEntityRemoveEvent(self, cause); // CraftBukkit
        final boolean alreadyRemoved = this.removalReason != null; // Paper - Folia schedulers
        if (this.removalReason == null) {
            this.removeEventCause = cause; // Paper
        }
        self.setRemoved(reason);
        // Paper start - Folia schedulers
        if (!(self instanceof ServerPlayer) && reason != Entity.RemovalReason.CHANGED_DIMENSION && !alreadyRemoved) {
            // Players need to be special cased, because they are regularly removed from the world
            this.retireScheduler();
        }
        // Paper end - Folia schedulers
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
}
