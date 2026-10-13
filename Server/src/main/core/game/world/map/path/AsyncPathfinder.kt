package core.game.world.map.path

import core.game.world.map.Location
import core.ServerConstants
import core.game.world.GameWorld
import core.game.world.map.RegionManager
import core.tools.PerformanceProbe
import core.worker.BackgroundWorkers

/**
 * Optional background path calculation. Collision flags are copied on the
 * game thread; the worker never calls RegionManager or touches entity state.
 * Consumers must validate the entity's current location before applying a path.
 */
object AsyncPathfinder {
    private data class PathSnapshot(
        val from: Location,
        val to: Location,
        val moverSize: Int,
        val targetSize: Int,
        val baseX: Int,
        val baseY: Int,
        val plane: Int,
        val flags: IntArray
    )

    private var lastSnapshotTick = -1
    private var snapshotCount = 0

    fun plan(from: Location, to: Location, moverSize: Int, targetSize: Int = 1, callback: (Path) -> Unit): Boolean {
        if (from.z != to.z || !from.withinDistance(to, ServerConstants.MAX_PATHFIND_DISTANCE)) return false
        // Snapshotting live collision is itself main-thread work: cap it at one per tick.
        if (lastSnapshotTick != GameWorld.ticks) {
            lastSnapshotTick = GameWorld.ticks
            snapshotCount = 0
        }
        if (snapshotCount++ >= 1 || BackgroundWorkers.pendingJobs() >= 100) {
            PerformanceProbe.counter("background.paths.snapshotSkipped")
            return false
        }
        val snapshotStarted = System.nanoTime()
        val fromCopy = Location.create(from.x, from.y, from.z)
        val toCopy = Location.create(to.x, to.y, to.z)
        val baseX = (from.regionX - 6) shl 3
        val baseY = (from.regionY - 6) shl 3
        // No live world reads are permitted in operation passed to BackgroundWorkers.
        val flags = IntArray(104 * 104)
        for (x in 0 until 104) for (y in 0 until 104) {
            flags[x * 104 + y] = RegionManager.getClippingFlag(from.z, baseX + x, baseY + y)
        }
        val snapshot = PathSnapshot(fromCopy, toCopy, moverSize, targetSize, baseX, baseY, from.z, flags)
        PerformanceProbe.recordBackground("path.snapshot", System.nanoTime() - snapshotStarted)
        PerformanceProbe.counter("background.paths.submitted")
        return BackgroundWorkers.plan(snapshot, { data ->
            val supplier = ClipMaskSupplier { z, x, y ->
                val dx = x - data.baseX
                val dy = y - data.baseY
                if (z != data.plane || dx !in 0..103 || dy !in 0..103) 0xFFFFFF
                else data.flags[dx * 104 + dy]
            }
            SmartPathfinder().find(
                data.from, data.moverSize, data.to, data.targetSize, data.targetSize,
                0, 0, 0, true, supplier
            )
        }, callback)
    }
}
