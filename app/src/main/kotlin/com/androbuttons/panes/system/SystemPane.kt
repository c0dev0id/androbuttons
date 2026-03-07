package com.androbuttons.panes.system

import android.content.Context
import android.net.TrafficStats
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
import android.content.Intent
import android.content.IntentFilter
import com.androbuttons.BatteryView
import com.androbuttons.LoadHistogramView
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

    private var loadHistogramView: LoadHistogramView? = null
    private var memoryBarView:  MemoryBarView?  = null
    private var diskSpaceView:  DiskSpaceView?  = null
    private var batteryView:    BatteryView?    = null
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

        // --- Load ---
        loadHistogramView = addSection("LOAD", LoadHistogramView(ctx))

        // --- Memory ---
        memoryBarView = addSection("MEMORY",     MemoryBarView(ctx))

        // --- Disk Space ---
        diskSpaceView = addSection("DISK SPACE", DiskSpaceView(ctx))

        // --- Battery ---
        batteryView   = addSection("BATTERY",    BatteryView(ctx))

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

        // Network I/O state
        private var prevNetRxBytes:  Long = 0L
        private var prevNetTxBytes:  Long = 0L
        private var prevNetTimeMs:   Long = 0L

        private val poller = object : Runnable {
            override fun run() {
                Thread {
                    val loadResult    = readLoadAvg()
                    val memResult     = readMemInfo()
                    val diskSpResult  = readDiskSpace()
                    val battResult    = readBattery()
                    val netResult     = readNetworkIO()
                    val signalResult  = readSignalStrengths()

                    handler.post {
                        loadResult?.let { load ->
                            loadHistogramView?.update(load)
                        }
                        memResult?.let { (used, cached, total) ->
                            memoryBarView?.update(used, cached, total)
                        }
                        diskSpResult?.let { (used, total) ->
                            diskSpaceView?.update(used, total)
                        }
                        val (tempC, voltMv) = battResult
                        batteryView?.update(tempC, voltMv)
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
        // /proc/loadavg  →  1-minute load average
        //
        // Format: "0.15 0.20 0.10 1/246 12345"
        // Field 0 is the 1-minute load average. This file is readable on all
        // Android versions including Android 10+ (unlike /proc/stat).
        // ---------------------------------------------------------------------
        private fun readLoadAvg(): Float? {
            return try {
                val line = BufferedReader(FileReader("/proc/loadavg")).use { it.readLine() }
                    ?: return null
                line.trim().split(Regex("\\s+")).getOrNull(0)?.toFloatOrNull()
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
        // Battery intent  →  (tempC, voltMv)
        //
        // ACTION_BATTERY_CHANGED is a sticky broadcast; registering a null
        // receiver returns the last broadcast without needing a persistent
        // listener. No permissions required.
        // ---------------------------------------------------------------------
        private fun readBattery(): Pair<Float?, Int?> {
            return try {
                val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val tempC  = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                                 ?.takeIf { it > Int.MIN_VALUE }
                                 ?.let { it / 10f }
                val voltMv = intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                                 ?.takeIf { it > Int.MIN_VALUE }
                Pair(tempC, voltMv)
            } catch (_: Exception) { Pair(null, null) }
        }

        // ---------------------------------------------------------------------
        // TrafficStats  →  (rxKBps, txKBps) for all interfaces
        //
        // Replaces /proc/net/dev which is blocked by SELinux on Android 10+
        // on many vendor builds. TrafficStats requires no permissions and has
        // been available since API 8.
        // ---------------------------------------------------------------------
        private fun readNetworkIO(): Pair<Float, Float>? {
            return try {
                val totalRx = TrafficStats.getTotalRxBytes()
                val totalTx = TrafficStats.getTotalTxBytes()
                // TrafficStats.UNSUPPORTED == -1; bail out if the kernel doesn't support it
                if (totalRx < 0 || totalTx < 0) return null

                val nowMs    = System.currentTimeMillis()
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
