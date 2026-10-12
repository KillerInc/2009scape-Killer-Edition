package core.game.world.update

import core.game.node.entity.npc.NPC
import core.game.node.entity.player.Player
import core.game.node.item.GroundItemManager
import core.game.world.map.RegionManager
import core.game.world.repository.InitializingNodeList
import core.net.packet.PacketRepository
import core.net.packet.context.PlayerContext
import core.net.packet.out.ClearMinimapFlag
import core.game.world.repository.Repository
import core.integrations.grafana.*
import core.tools.PerformanceProbe

/**
 * The entity update sequence.
 * @author Emperor
 */
class UpdateSequence
/**
 * Constructs a new `ParallelUpdatingSequence` `Object`.
 */
{
    var lobbyList: List<Player>? = null
    var playersList: List<Player>? = null
    var npcList: List<NPC>? = null

    /**
     * Starts the update sequence.
     * @return `True` if we should continue.
     */
    fun start() {
        lobbyList = Repository.lobbyPlayers
        playersList = renderablePlayers
        npcList = Repository.renderableNpcs
        lobbyList!!.map{ PacketRepository.send(ClearMinimapFlag::class.java, PlayerContext(it)) }

        var probe = PerformanceProbe.begin()
        val npcTickStart = System.currentTimeMillis()
        npcList!!.forEach(NPC::tick)
        Grafana.npcTickTime = (System.currentTimeMillis() - npcTickStart).toInt()
        PerformanceProbe.phase("entities.npcTick", probe)

        probe = PerformanceProbe.begin()
        val playerTickStart = System.currentTimeMillis()
        renderablePlayers.forEach(Player::tick)
        Grafana.playerTickTime = (System.currentTimeMillis() - playerTickStart).toInt()
        PerformanceProbe.phase("entities.playerTick", probe)
    }

    /**
     * Runs the updating part of the sequence.
     */
    fun run() {
        val probe = PerformanceProbe.begin()
        val playerRenderStart = System.currentTimeMillis()
        renderablePlayers.forEach(Player::update)
        Grafana.playerRenderTime = (System.currentTimeMillis() - playerRenderStart).toInt()
        PerformanceProbe.phase("entities.playerUpdate", probe)
    }

    /**
     * Ends the sequence, calls the [Entity.reset] method..
     */
    fun end() {
        var probe = PerformanceProbe.begin()
        playersList!!.forEach(Player::reset)
        PerformanceProbe.phase("entities.playerReset", probe)

        probe = PerformanceProbe.begin()
        npcList!!.forEach(NPC::reset)
        PerformanceProbe.phase("entities.npcReset", probe)

        probe = PerformanceProbe.begin()
        renderablePlayers.sync()
        PerformanceProbe.phase("entities.playerSync", probe)

        probe = PerformanceProbe.begin()
        ChunkUpdateTracker.resetAll()
        PerformanceProbe.phase("chunks.reset", probe)

        probe = PerformanceProbe.begin()
        GroundItemManager.pulse()
        PerformanceProbe.phase("groundItems.pulse", probe)
    }

    /**
     * Terminates the update sequence.
     */
    fun terminate() {
    }

    companion object {
        /**
         * Gets the renderablePlayers.
         * @return The renderablePlayers.
         */
        /**
         * The list of active players.
         */
        @JvmStatic
        val renderablePlayers = InitializingNodeList<Player>()

    }
}
