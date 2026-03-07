package com.androbuttons.panes.system

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.telephony.TelephonyManager
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.androbuttons.CpuCoresView
import com.androbuttons.DiskIoView
import com.androbuttons.DiskSpaceView
import com.androbuttons.MemoryBarView
import com.androbuttons.NetworkIoView
import com.androbuttons.SignalView
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.dpWith
import java.io.BufferedReader
import java.io.FileReader

class SystemPane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "System"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private var cpuCoresView:   CpuCoresView?   = null
    private var memoryBarView:  MemoryBarView?  = null
    private var diskSpaceView:  DiskSpaceView?  = null
    private var diskIoView:     DiskIoView?     = null
    private var networkIoView:  NetworkIoView?  = null
    private var signalView:     SignalView?      = null

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

        fun <V : View> addSection(header: String, view: V): V {
            inner.addView(sectionHeader(header))
            inner.addView(view.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            inner.addView(spacer())
            return view
        }

        // --- CPU ---
        cpuCoresView  = addSection("CPU",        CpuCoresView(ctx))

        // --- Memory ---
        memoryBarView = addSection("MEMORY",     MemoryBarView(ctx))

        // --- Disk Space ---
        diskSpaceView = addSection("DISK SPACE", DiskSpaceView(ctx))

        // --- Disk I/O ---
        diskIoView    = addSection("DISK I/O",   DiskIoView(ctx))

        // --- Network I/O ---
        networkIoView = addSection("NETWORK I/O", NetworkIoView(ctx))

        // --- Signal ---
        signalView    = addSection("SIGNAL",     SignalView(ctx))

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
        diskIoView?.maxSeen    = 1f
        networkIoView?.maxSeen = 1f
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

        // CPU: previous snapshot per core
        private var prevCpuStats: Array<LongArray>? = null

        // Disk I/O state
        private var prevDiskRead:   Long   = 0L
        private var prevDiskWrite:  Long   = 0L
        private var prevDiskTimeMs: Long   = 0L
        private var diskDevice:     String? = null

        // Network I/O state
        private var prevNetRxBytes:  Long = 0L
        private var prevNetTxBytes:  Long = 0L
        private var prevNetTimeMs:   Long = 0L

        private val poller = object : Runnable {
            override fun run() {
                Thread {
                    val cpuResult     = readCpuUsages()
                    val memResult     = readMemInfo()
                    val diskSpResult  = readDiskSpace()
                    val diskIoResult  = readDiskIO()
                    val netResult     = readNetworkIO()
                    val signalResult  = readSignalStrengths()

                    handler.post {
                        cpuResult?.let { usages ->
                            cpuCoresView?.update(usages)
                            cpuCoresView?.requestLayout()
                        }
                        memResult?.let { (used, cached, total) ->
                            memoryBarView?.update(used, cached, total)
                        }
                        diskSpResult?.let { (used, total) ->
                            diskSpaceView?.update(used, total)
                        }
                        diskIoResult?.let { (readMBps, writeMBps) ->
                            diskIoView?.update(readMBps, writeMBps)
                        }
                        netResult?.let { (rxKBps, txKBps) ->
                            networkIoView?.update(rxKBps, txKBps)
                        }
                        val (wifiRssi, mobileDbm) = signalResult
                        signalView?.update(wifiRssi, mobileDbm)
                    }
                }.start()

                handler.postDelayed(this, 1000L)
            }
        }

        fun start() { handler.post(poller) }
        fun stop()  { handler.removeCallbacks(poller) }

        // ---------------------------------------------------------------------
        // /proc/stat  →  per-core usage fractions
        // ---------------------------------------------------------------------
        private fun readCpuUsages(): FloatArray? {
            return try {
                val lines = BufferedReader(FileReader("/proc/stat")).use { it.readLines() }
                val coreLines = lines.filter { it.matches(Regex("cpu\\d+.*")) }
                if (coreLines.isEmpty()) return null

                val currentStats = Array(coreLines.size) { i ->
                    val parts = coreLines[i].split(" ").drop(1).map { it.toLongOrNull() ?: 0L }
                    LongArray(8) { j -> parts.getOrElse(j) { 0L } }
                }

                val prev = prevCpuStats
                prevCpuStats = currentStats
                if (prev == null || prev.size != currentStats.size) return null

                FloatArray(currentStats.size) { i ->
                    val cur = currentStats[i]; val prv = prev[i]
                    val idleCur  = cur[3] + cur[4]
                    val idlePrev = prv[3] + prv[4]
                    val dTotal   = cur.sum() - prv.sum()
                    val dIdle    = idleCur - idlePrev
                    if (dTotal <= 0L) 0f else (1f - dIdle.toFloat() / dTotal).coerceIn(0f, 1f)
                }
            } catch (_: Exception) { null }
        }

        // ---------------------------------------------------------------------
        // /proc/meminfo  →  (usedMb, cachedMb, totalMb)
        // ---------------------------------------------------------------------
        private fun readMemInfo(): Triple<Long, Long, Long>? {
            return try {
                val map = mutableMapOf<String, Long>()
                BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                    reader.lineSequence().forEach { line ->
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) map[parts[0].trimEnd(':')] = parts[1].toLongOrNull() ?: 0L
                    }
                }
                val total        = (map["MemTotal"]     ?: return null) / 1024L
                val free         = (map["MemFree"]      ?: 0L) / 1024L
                val buffers      = (map["Buffers"]      ?: 0L) / 1024L
                val cached       = (map["Cached"]       ?: 0L) / 1024L
                val sReclaimable = (map["SReclaimable"] ?: 0L) / 1024L
                val shmem        = (map["Shmem"]        ?: 0L) / 1024L
                val cachedTotal  = buffers + cached + sReclaimable - shmem
                val used         = total - free - buffers - (cached + sReclaimable - shmem)
                Triple(used.coerceAtLeast(0L), cachedTotal.coerceAtLeast(0L), total)
            } catch (_: Exception) { null }
        }

        // ---------------------------------------------------------------------
        // StatFs  →  (usedBytes, totalBytes) for internal storage
        // ---------------------------------------------------------------------
        private fun readDiskSpace(): Pair<Long, Long>? {
            return try {
                val stat  = StatFs(Environment.getDataDirectory().path)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free  = stat.availableBlocksLong * stat.blockSizeLong
                Pair((total - free).coerceAtLeast(0L), total)
            } catch (_: Exception) { null }
        }

        // ---------------------------------------------------------------------
        // /proc/diskstats  →  (readMBps, writeMBps)
        //
        // Device detection is intentionally broad: sd[a-z], mmcblk\d+,
        // nvme\d+n\d+, and dm-\d+ (device-mapper, common on encrypted Android).
        // When multiple candidates exist, the one with the highest cumulative
        // sector count (reads + writes) is chosen so the most-active storage
        // path wins automatically.
        // ---------------------------------------------------------------------
        private fun readDiskIO(): Pair<Float, Float>? {
            return try {
                val lines = BufferedReader(FileReader("/proc/diskstats")).use { it.readLines() }
                val parsed = lines.map { it.trim().split(Regex("\\s+")) }

                if (diskDevice == null) {
                    val candidates = parsed.filter { parts ->
                        parts.size >= 14 &&
                        (parts[2].matches(Regex("sd[a-z]"))       ||
                         parts[2].matches(Regex("mmcblk\\d+"))    ||
                         parts[2].matches(Regex("nvme\\d+n\\d+")) ||
                         parts[2].matches(Regex("dm-\\d+")))
                    }
                    diskDevice = candidates.maxByOrNull { parts ->
                        (parts.getOrNull(5)?.toLongOrNull() ?: 0L) +
                        (parts.getOrNull(9)?.toLongOrNull() ?: 0L)
                    }?.getOrNull(2)
                }

                val device = diskDevice ?: return null
                val parts  = parsed.firstOrNull { it.getOrNull(2) == device } ?: return null

                val sectorsRead  = parts.getOrNull(5)?.toLongOrNull() ?: return null
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
            } catch (_: Exception) { null }
        }

        // ---------------------------------------------------------------------
        // /proc/net/dev  →  (rxKBps, txKBps) summed across all non-loopback ifaces
        // ---------------------------------------------------------------------
        private fun readNetworkIO(): Pair<Float, Float>? {
            return try {
                val lines = BufferedReader(FileReader("/proc/net/dev")).use { it.readLines() }
                // Skip first 2 header lines; columns: iface | rx_bytes … | tx_bytes …
                // rx_bytes = col 1, tx_bytes = col 9 (0-based after splitting on whitespace)
                var totalRx = 0L
                var totalTx = 0L
                for (line in lines.drop(2)) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    // Format: "iface: bytes packets errs drop fifo frame compressed multicast  bytes …"
                    val colonIdx = trimmed.indexOf(':')
                    if (colonIdx < 0) continue
                    val iface = trimmed.substring(0, colonIdx).trim()
                    if (iface == "lo") continue
                    val fields = trimmed.substring(colonIdx + 1).trim().split(Regex("\\s+"))
                    totalRx += fields.getOrNull(0)?.toLongOrNull() ?: 0L
                    totalTx += fields.getOrNull(8)?.toLongOrNull() ?: 0L
                }

                val nowMs = System.currentTimeMillis()
                val prevRx   = prevNetRxBytes
                val prevTx   = prevNetTxBytes
                val prevTime = prevNetTimeMs

                prevNetRxBytes = totalRx
                prevNetTxBytes = totalTx
                prevNetTimeMs  = nowMs

                if (prevTime == 0L) return Pair(0f, 0f)

                val elapsedSec = (nowMs - prevTime) / 1000f
                if (elapsedSec <= 0f) return Pair(0f, 0f)

                val rxKBps = ((totalRx - prevRx).coerceAtLeast(0L) / 1024f) / elapsedSec
                val txKBps = ((totalTx - prevTx).coerceAtLeast(0L) / 1024f) / elapsedSec
                Pair(rxKBps, txKBps)
            } catch (_: Exception) { null }
        }

        // ---------------------------------------------------------------------
        // WifiManager + TelephonyManager  →  (wifiRssi?, mobileDbm?)
        // ---------------------------------------------------------------------
        private fun readSignalStrengths(): Pair<Int?, Int?> {
            val wifiRssi: Int? = try {
                @Suppress("DEPRECATION")
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val rssi = wm?.connectionInfo?.rssi
                // WifiInfo.INVALID_RSSI == -127; values <= -127 mean not connected
                if (rssi == null || rssi == Int.MIN_VALUE || rssi <= -127) null else rssi
            } catch (_: Exception) { null }

            val mobileDbm: Int? = try {
                // getCellSignalStrengths() requires API 29 (Q)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    tm?.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
                        ?.takeIf { it > Int.MIN_VALUE && it > -200 }
                } else {
                    null
                }
            } catch (_: SecurityException) { null } catch (_: Exception) { null }

            return Pair(wifiRssi, mobileDbm)
        }
    }
}
