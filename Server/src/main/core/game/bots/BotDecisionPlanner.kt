package core.game.bots

import core.game.node.Node
import core.game.world.map.Location
import core.tools.PerformanceProbe
import core.worker.BackgroundWorkers

/**
 * Pre-sorts immutable target-coordinate snapshots off-thread. The game thread
 * still evaluates target validity and executes all bot actions synchronously.
 */
class BotDecisionPlanner {
    private data class Target(val index: Int, val distanceSquared: Long)
    private data class Request(val x: Int, val y: Int, val z: Int, val distances: List<Target>)
    private data class Ranked(val x: Int, val y: Int, val z: Int, val indices: IntArray)

    private var ready: Ranked? = null
    private var pending = false

    fun order(start: Location, candidates: List<Node>): IntArray? {
        val available = ready
        if (available != null && available.x == start.x && available.y == start.y &&
            available.z == start.z && available.indices.size == candidates.size) {
            PerformanceProbe.counter("background.botPlans.used")
            return available.indices
        }

        if (!pending && candidates.size >= 12) {
            val immutable = candidates.mapIndexed { index, node ->
                val dx = (node.location.x - start.x).toLong()
                val dy = (node.location.y - start.y).toLong()
                Target(index, dx * dx + dy * dy)
            }
            val request = Request(start.x, start.y, start.z, immutable)
            pending = BackgroundWorkers.plan(request, { snapshot ->
                Ranked(snapshot.x, snapshot.y, snapshot.z,
                    snapshot.distances.sortedWith(compareBy<Target> { it.distanceSquared }.thenBy { it.index })
                        .map { it.index }.toIntArray())
            }, { result ->
                ready = result
                pending = false
            })
            if (pending) PerformanceProbe.counter("background.botPlans.submitted")
        }
        return null // Existing synchronous selection is always a safe fallback.
    }
}
