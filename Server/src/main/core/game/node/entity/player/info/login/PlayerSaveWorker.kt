package core.game.node.entity.player.info.login

import core.ServerConstants
import core.game.node.entity.player.Player
import core.tools.PerformanceProbe
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Capture a full JSON snapshot on the game thread, then perform only disk I/O
 * on a single ordered worker. Never read mutable Player fields on the worker.
 */
object PlayerSaveWorker {
    private val io = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(256),
        ThreadFactory { work -> Thread(work, "killer-player-save-io").apply { isDaemon = true } },
        // Never discard a player save; in an extreme backlog fail safe to a synchronous write.
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    fun enqueue(player: Player) {
        if (!player.details.saveParsed) return
        val begin = System.nanoTime()
        val json = PlayerSaver(player).populate().toJSONString()
        val filename = player.name + ".json"
        PerformanceProbe.recordBackground("save.snapshot", System.nanoTime() - begin)
        io.execute {
            try {
                val start = System.nanoTime()
                val root: Path = Paths.get(ServerConstants.PLAYER_SAVE_PATH)
                Files.createDirectories(root)
                val target = root.resolve(filename)
                val temp = root.resolve(filename + ".pending")
                Files.write(temp, json.toByteArray(StandardCharsets.UTF_8))
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                PerformanceProbe.recordBackground("save.disk", System.nanoTime() - start)
                PerformanceProbe.counter("background.saves.completed")
            } catch (error: Throwable) {
                PerformanceProbe.counter("background.saves.failed")
                System.err.println("[PlayerSave] Failed to save " + filename + ": " + error.message)
            }
        }
    }

    /** Called from the JVM shutdown hook to preserve queued player saves. */
    @JvmStatic
    fun flush() {
        val done = CountDownLatch(1)
        try {
            io.execute { done.countDown() }
            if (!done.await(15, TimeUnit.SECONDS)) {
                System.err.println("[PlayerSave] Timed out waiting for queued saves.")
            }
        } catch (error: Throwable) {
            System.err.println("[PlayerSave] Could not flush saves: " + error.message)
        }
    }
}
