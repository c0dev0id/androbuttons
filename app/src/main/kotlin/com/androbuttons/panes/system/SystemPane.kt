package com.androbuttons.panes.system

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.androbuttons.CpuCoresView
import com.androbuttons.DiskIoView
import com.androbuttons.MemoryBarView
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.dpWith
import java.io.BufferedReader
import java.io.FileReader

class SystemPane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "System"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private var cpuCoresView: CpuCoresView? = null
    private var memoryBarView: MemoryBarView? = null
    private var diskIoView: DiskIoView? = null

    private var monitor: SystemMonitor? = null

    // -------------------------------------------------------------------------
    // PaneContent
    // -------------------------------------------------------------------------

    override fun buildView(): View {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
        }

        fun sectionHeader(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 9f
            setTextColor(Color.parseColor("#F57C00"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }
        }

        fun spacer() = Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8.dp())
        }

        // --- CPU ---
        inner.addView(sectionHeader("CPU"))
        cpuCoresView = CpuCoresView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(cpuCoresView)
        inner.addView(spacer())

        // --- Memory ---
        inner.addView(sectionHeader("MEMORY"))
        memoryBarView = MemoryBarView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(memoryBarView)
        inner.addView(spacer())

        // --- Disk I/O ---
        inner.addView(sectionHeader("DISK I/O"))
        diskIoView = DiskIoView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(diskIoView)

        startMonitor()

        return ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
            addView(inner)
        }
    }

    override fun onResumed() = startMonitor()

    override fun onPaused() {
        monitor?.stop()
        monitor = null
        diskIoView?.maxSeen = 1f
    }

    override fun onDestroy() {
        monitor?.stop()
        monitor = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun startMonitor() {
        if (monitor == null) {
            monitor = SystemMonitor().also { it.start() }
        }
    }

    // -------------------------------------------------------------------------
    // Inner: SystemMonitor — polls /proc files every second
    // -------------------------------------------------------------------------

    private inner class SystemMonitor {

        private val handler = Handler(Looper.getMainLooper())

        // CPU: previous snapshot per core — [user, nice, system, idle, iowait, irq, softirq, steal]
        private var prevCpuStats: Array<LongArray>? = null

        // Disk: previous [sectorsRead, sectorsWritten] and timestamp
        private var prevDiskRead:    Long = 0L
        private var prevDiskWrite:   Long = 0L
        private var prevDiskTimeMs:  Long = 0L
        private var diskDevice:      String? = null

        private val poller = object : Runnable {
            override fun run() {
                // Read all proc data on a background thread, then post results to main thread
                Thread {
                    val cpuResult  = readCpuUsages()
                    val memResult  = readMemInfo()
                    val diskResult = readDiskIO()

                    handler.post {
                        cpuResult?.let { usages ->
                            cpuCoresView?.update(usages)
                            cpuCoresView?.requestLayout()
                        }
                        memResult?.let { (used, cached, total) ->
                            memoryBarView?.update(used, cached, total)
                        }
                        diskResult?.let { (readMBps, writeMBps) ->
                            diskIoView?.update(readMBps, writeMBps)
                        }
                    }
                }.start()

                handler.postDelayed(this, 1000L)
            }
        }

        fun start() {
            handler.post(poller)
        }

        fun stop() {
            handler.removeCallbacks(poller)
        }

        // Parse /proc/stat, compute per-core usage fractions
        private fun readCpuUsages(): FloatArray? {
            return try {
                val lines = BufferedReader(FileReader("/proc/stat")).use { it.readLines() }
                // Lines starting with "cpu0", "cpu1", … (skip aggregate "cpu" line)
                val coreLines = lines.filter { it.matches(Regex("cpu\\d+.*")) }
                if (coreLines.isEmpty()) return null

                val currentStats = Array(coreLines.size) { i ->
                    val parts = coreLines[i].split(" ").drop(1).map { it.toLongOrNull() ?: 0L }
                    LongArray(8) { j -> parts.getOrElse(j) { 0L } }
                    // indices: 0=user 1=nice 2=system 3=idle 4=iowait 5=irq 6=softirq 7=steal
                }

                val prev = prevCpuStats
                prevCpuStats = currentStats

                if (prev == null || prev.size != currentStats.size) return null

                FloatArray(currentStats.size) { i ->
                    val cur = currentStats[i]
                    val prv = prev[i]
                    val idleCur   = cur[3] + cur[4]   // idle + iowait
                    val idlePrev  = prv[3] + prv[4]
                    val totalCur  = cur.sum()
                    val totalPrev = prv.sum()
                    val dTotal = totalCur - totalPrev
                    val dIdle  = idleCur  - idlePrev
                    if (dTotal <= 0L) 0f else (1f - dIdle.toFloat() / dTotal).coerceIn(0f, 1f)
                }
            } catch (_: Exception) {
                null
            }
        }

        // Parse /proc/meminfo, return (usedMb, cachedMb, totalMb)
        private fun readMemInfo(): Triple<Long, Long, Long>? {
            return try {
                val map = mutableMapOf<String, Long>()
                BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                    reader.lineSequence().forEach { line ->
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val key = parts[0].trimEnd(':')
                            val value = parts[1].toLongOrNull() ?: 0L
                            map[key] = value
                        }
                    }
                }
                val total        = (map["MemTotal"]       ?: return null) / 1024L
                val free         = (map["MemFree"]        ?: 0L) / 1024L
                val buffers      = (map["Buffers"]        ?: 0L) / 1024L
                val cached       = (map["Cached"]         ?: 0L) / 1024L
                val sReclaimable = (map["SReclaimable"]   ?: 0L) / 1024L
                val shmem        = (map["Shmem"]          ?: 0L) / 1024L

                val cachedTotal = buffers + cached + sReclaimable - shmem
                val used = total - free - buffers - (cached + sReclaimable - shmem)

                Triple(used.coerceAtLeast(0L), cachedTotal.coerceAtLeast(0L), total)
            } catch (_: Exception) {
                null
            }
        }

        // Parse /proc/diskstats, return (readMBps, writeMBps)
        private fun readDiskIO(): Pair<Float, Float>? {
            return try {
                val lines = BufferedReader(FileReader("/proc/diskstats")).use { it.readLines() }

                // Find main block device on first call
                if (diskDevice == null) {
                    diskDevice = lines
                        .map { it.trim().split(Regex("\\s+")) }
                        .firstOrNull { parts ->
                            parts.size >= 14 &&
                            (parts[2].matches(Regex("sd[a-z]")) ||
                             parts[2].matches(Regex("mmcblk\\d+")) ||
                             parts[2].matches(Regex("nvme\\d+n\\d+")))
                        }
                        ?.getOrNull(2)
                }

                val device = diskDevice ?: return null

                val parts = lines
                    .map { it.trim().split(Regex("\\s+")) }
                    .firstOrNull { it.getOrNull(2) == device }
                    ?: return null

                // diskstats field layout (0-based after the 3 id fields):
                // idx 3 = reads completed, idx 5 = sectors read
                // idx 7 = writes completed, idx 9 = sectors written
                val sectorsRead  = parts.getOrNull(5)?.toLongOrNull()  ?: return null
                val sectorsWrite = parts.getOrNull(9)?.toLongOrNull() ?: return null
                val nowMs = System.currentTimeMillis()

                val prevRead  = prevDiskRead
                val prevWrite = prevDiskWrite
                val prevTime  = prevDiskTimeMs

                prevDiskRead   = sectorsRead
                prevDiskWrite  = sectorsWrite
                prevDiskTimeMs = nowMs

                if (prevTime == 0L) return Pair(0f, 0f)

                val elapsedSec = (nowMs - prevTime) / 1000f
                if (elapsedSec <= 0f) return Pair(0f, 0f)

                val readMBps  = ((sectorsRead  - prevRead)  * 512L / 1_000_000f) / elapsedSec
                val writeMBps = ((sectorsWrite - prevWrite) * 512L / 1_000_000f) / elapsedSec

                Pair(readMBps.coerceAtLeast(0f), writeMBps.coerceAtLeast(0f))
            } catch (_: Exception) {
                null
            }
        }
    }
}
