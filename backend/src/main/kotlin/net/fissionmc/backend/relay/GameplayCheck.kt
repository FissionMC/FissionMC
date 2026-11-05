package net.fissionmc.backend.relay

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.logging.Logger

/**
 * Dev check (`-PcheckGameplay=1`): once a relayed player has been in the world for 100 ticks it exercises the paths the user reported
 * as broken ("I can't pick up / break blocks") server-side and logs what happened. Runs on the server thread.
 */
object GameplayCheck {
    private val log = Logger.getLogger("FissionMC")
    private val enabled = System.getProperty("fission.check-gameplay") != null
    private var joinedTick = HashMap<java.util.UUID, Int>()
    private val done = HashSet<java.util.UUID>()
    private var pending: Pair<Int, () -> Unit>? = null
    private var tick = 0

    @JvmStatic fun tick(server: MinecraftServer) {
        if (!enabled) return
        tick++
        if (tick == 1) log.info("[fission] gameplay check armed")
        if (tick % 200 == 0 && server.playerList.players.isNotEmpty()) log.info("[fission] gameplay check: tick $tick players=${server.playerList.players.size}")
        pending?.let { (at, f) -> if (tick >= at) { pending = null; runCatching { f() }.onFailure { log.warning("[fission] gameplay check step failed: $it") } } }
        for (p in server.playerList.players) {
            val first = joinedTick.getOrPut(p.uuid) { tick }
            if (tick - first >= 40 && done.add(p.uuid)) runCatching { run(server, p) }.onFailure { log.warning("[fission] gameplay check failed: $it") }
        }
    }

    private fun run(server: MinecraftServer, p: ServerPlayer) {
        val level = p.level()
        log.info("[fission] gameplay: server avg tick ${"%.2f".format(server.averageTickTimeNanos / 1e6)} ms (50 ms budget), tickCount ${server.tickCount}")
        log.info("[fission] gameplay: ${p.plainTextName} mode=${p.gameMode.gameModeForPlayer} invulnerableAbility=${p.abilities.invulnerable} mayBuild=${p.abilities.mayBuild} health=${p.health} invulnerableTime=${p.invulnerableTime} op=${server.playerList.isOp(p.nameAndId())}")

        // 2. break the block below the player
        val below = BlockPos(p.blockPosition().x + 2, p.blockPosition().y, p.blockPosition().z)
        level.setBlock(below, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3)   // dirt drops itself without a tool
        val state = level.getBlockState(below)
        val itemsBefore = level.getEntitiesOfClass(ItemEntity::class.java, p.boundingBox.inflate(6.0)).size
        val broke = p.gameMode.destroyBlock(below)
        val after = level.getBlockState(below)
        log.info("[fission] gameplay: destroyBlock($below ${state.block}) returned $broke, block now ${after.block}, item entities nearby $itemsBefore -> ${level.getEntitiesOfClass(ItemEntity::class.java, p.boundingBox.inflate(6.0)).size}")

        // 3. pick up a dropped item
        val drop = ItemEntity(level, p.x, p.y, p.z, ItemStack(Items.DIAMOND, 3))
        drop.setNoPickUpDelay()
        val added = level.addFreshEntity(drop)
        val had = p.inventory.countItem(Items.DIAMOND)
        pending = tick + 10 to {
            val area = p.boundingBox.inflate(1.0, 0.5, 1.0)
            val seen = level.getEntities(p, area).contains(drop)
            log.info("[fission] gameplay: pickup addFreshEntity=$added diamonds $had -> ${p.inventory.countItem(Items.DIAMOND)}, item alive=${drop.isAlive}, removed=${drop.isRemoved}, getEntities(player, area) contains item=$seen, spectator=${p.isSpectator}, canHold=${p.inventory.canHold(drop.item)}")
            drop.playerTouch(p)
            log.info("[fission] gameplay: after a direct playerTouch: diamonds ${p.inventory.countItem(Items.DIAMOND)}, item alive=${drop.isAlive}")
        }
    }
}
