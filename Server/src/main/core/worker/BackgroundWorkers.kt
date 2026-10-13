package core.worker

import core.tools.PerformanceProbe
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Only immutable snapshots are allowed into this pool. Callbacks run on the
 * authoritative game tick, never on background worker threads.
 */
object BackgroundWorkers {
    private val threadIds = AtomicInteger()
    private val results = ConcurrentLinkedQueue<() -> Unit>()
    private val pool = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(128),
        ThreadFactory { runnable ->
            Thread(runnable, "killer-planning-" + threadIds.incrementAndGet()).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun <T, R> plan(snapshot: T, operation: (T) -> R, complete: (R) -> Unit): Boolean {
        return try {
            pool.execute {
                try {
                    val start = System.nanoTime()
                    val result = operation(snapshot)
                    PerformanceProbe.counter("background.plans.completed")
                    PerformanceProbe.recordBackground("planning", System.nanoTime() - start)
                    results.add { complete(result) }
                } catch (error: Throwable) {
                    PerformanceProbe.counter("background.plans.failed")
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            PerformanceProbe.counter("background.plans.rejected")
            false
        }
    }

    /** Bounded drain so a burst of completed jobs cannot monopolize a game tick. */
    fun drain(max: Int = 64) {
        repeat(max) {
            val completion = results.poll() ?: return
            try {
                completion()
            } catch (_: Throwable) {
                PerformanceProbe.counter("background.callbacks.failed")
            }
        }
    }

    fun pendingJobs(): Int = pool.queue.size
    fun pendingCallbacks(): Int = results.size
}
