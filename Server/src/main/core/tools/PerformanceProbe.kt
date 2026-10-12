package core.tools

import core.game.world.GameWorld
import core.game.world.repository.Repository
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object PerformanceProbe {
    private const val REPORT_EVERY_TICKS = 100L
    private const val SLOW_TICK_MS = 450.0
    private const val OVERRUN_TICK_MS = 600.0
    private const val SLOW_PHASE_MS = 50.0
    private const val TOP_PULSES = 12

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val phaseStats = ConcurrentHashMap<String, Stat>()
    private val pulseStats = ConcurrentHashMap<String, Stat>()
    private val counters = ConcurrentHashMap<String, Long>()
    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
    private val memoryBean = ManagementFactory.getMemoryMXBean()

    private var writer: BufferedWriter? = null
    private var tickCount = 0L
    private var windowStartMs = System.currentTimeMillis()
    private var lastGcCount = gcCount()
    private var lastGcTime = gcTime()

    @Synchronized
    private fun out(line: String) {
        try {
            if (writer == null) {
                val dir = File("logs")
                if (!dir.exists()) dir.mkdirs()
                writer = BufferedWriter(FileWriter(File(dir, "performance.log"), true), 64 * 1024)
                writer!!.write("# Killer Edition server performance log")
                writer!!.newLine()
                writer!!.write("# started=" + dateFormat.format(Date()))
                writer!!.newLine()
                writer!!.flush()
            }
            writer!!.write(line)
            writer!!.newLine()
            writer!!.flush()
        } catch (_: Throwable) {
        }
    }

    fun begin(): Long = System.nanoTime()

    fun phase(name: String, startedNs: Long): Double {
        val ms = (System.nanoTime() - startedNs) / 1_000_000.0
        phaseStats.computeIfAbsent(name) { Stat() }.add(ms)
        if (ms >= SLOW_PHASE_MS) {
            out(stamp() + " SLOW_PHASE name=" + name + " ms=" + fmt(ms) + " tick=" + GameWorld.ticks)
        }
        return ms
    }

    fun counter(name: String, amount: Long = 1L) {
        counters.merge(name, amount) { a, b -> a + b }
    }

    fun recordPulse(name: String, elapsedNs: Long) {
        val ms = elapsedNs / 1_000_000.0
        pulseStats.computeIfAbsent(name) { Stat() }.add(ms)
        if (ms >= SLOW_PHASE_MS) {
            out(stamp() + " SLOW_PULSE name=" + name + " ms=" + fmt(ms) + " tick=" + GameWorld.ticks)
        }
    }

    @Synchronized
    fun tick(totalMs: Double, pulseCount: Int) {
        phaseStats.computeIfAbsent("tick.total") { Stat() }.add(totalMs)
        tickCount++

        if (totalMs >= SLOW_TICK_MS) {
            val kind = if (totalMs >= OVERRUN_TICK_MS) "TICK_OVERRUN" else "SLOW_TICK"
            out(
                stamp() + " " + kind +
                    " ms=" + fmt(totalMs) +
                    " tick=" + GameWorld.ticks +
                    " players=" + Repository.players.count { it != null && !it.isArtificial } +
                    " npcs=" + Repository.renderableNpcs.size +
                    " pulses=" + pulseCount
            )
        }

        if (tickCount % REPORT_EVERY_TICKS == 0L) {
            report()
        }
    }

    @Synchronized
    private fun report() {
        val now = System.currentTimeMillis()
        val heap = memoryBean.heapMemoryUsage
        val currentGcCount = gcCount()
        val currentGcTime = gcTime()
        val realPlayers = Repository.players.count { it != null && !it.isArtificial }

        val phases = phaseStats.entries
            .sortedByDescending { it.value.totalMs }
            .joinToString(" ") {
                it.key + "(avg=" + fmt(it.value.avg()) +
                    ",max=" + fmt(it.value.maxMs) +
                    ",n=" + it.value.count + ")"
            }

        val counterText = counters.entries
            .sortedBy { it.key }
            .joinToString(" ") { it.key + "=" + it.value }

        val pulses = pulseStats.entries
            .sortedByDescending { it.value.totalMs }
            .take(TOP_PULSES)
            .joinToString(" ") {
                it.key.substringAfterLast('.') +
                    "(total=" + fmt(it.value.totalMs) +
                    ",avg=" + fmt(it.value.avg()) +
                    ",max=" + fmt(it.value.maxMs) +
                    ",n=" + it.value.count + ")"
            }

        out(
            stamp() + " SUMMARY" +
                " windowMs=" + (now - windowStartMs) +
                " ticks=" + REPORT_EVERY_TICKS +
                " players=" + realPlayers +
                " npcs=" + Repository.renderableNpcs.size +
                " heapUsedMB=" + (heap.used / (1024 * 1024)) +
                " heapCommittedMB=" + (heap.committed / (1024 * 1024)) +
                " heapMaxMB=" + (if (heap.max > 0) heap.max / (1024 * 1024) else -1) +
                " gcCountDelta=" + max(0L, currentGcCount - lastGcCount) +
                " gcTimeMsDelta=" + max(0L, currentGcTime - lastGcTime)
        )
        out(stamp() + " PHASES " + phases)
        if (counterText.isNotEmpty()) {
            out(stamp() + " COUNTERS " + counterText)
        }
        if (pulses.isNotEmpty()) {
            out(stamp() + " TOP_PULSES " + pulses)
        }

        phaseStats.clear()
        pulseStats.clear()
        counters.clear()
        windowStartMs = now
        lastGcCount = currentGcCount
        lastGcTime = currentGcTime
    }

    private fun gcCount(): Long =
        gcBeans.sumOf { if (it.collectionCount < 0) 0L else it.collectionCount }

    private fun gcTime(): Long =
        gcBeans.sumOf { if (it.collectionTime < 0) 0L else it.collectionTime }

    private fun stamp(): String = dateFormat.format(Date())

    private fun fmt(value: Double): String =
        String.format(Locale.ROOT, "%.3f", value)

    private class Stat {
        var count = 0L
        var totalMs = 0.0
        var maxMs = 0.0

        @Synchronized
        fun add(ms: Double) {
            count++
            totalMs += ms
            if (ms > maxMs) maxMs = ms
        }

        fun avg(): Double = if (count == 0L) 0.0 else totalMs / count
    }
}
