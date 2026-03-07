package com.androbuttons.panes.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.dpWith
import java.io.BufferedReader
import java.io.FileReader

class WidgetPane(private val bridge: ServiceBridge, private val paneId: String) : PaneContent {

    override val title: String
        get() = bridge.getStringPref("${paneId}_title", "Widgets") ?: "Widgets"

    enum class Widget(val id: String, val label: String) {
        CPU("cpu", "CPU"),
        MEMORY("memory", "MEMORY"),
        DISK_SPACE("disk_space", "DISK SPACE"),
        DISK_IO("disk_io", "DISK I/O"),
        NETWORK("network", "NETWORK I/O"),
        SIGNAL("signal", "SIGNAL")
    }

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private var paneRoot: LinearLayout? = null
    private var cpuView: CpuCoresView? = null
    private var memoryBarView: MemoryBarView? = null
    private var diskSpaceView: DiskSpaceView? = null
    private var diskIoView: DiskIoView? = null
    private var networkIoView: NetworkIoView? = null
    private var signalView: SignalView? = null
    private var monitor: WidgetMonitor? = null
    private var inConfigureView = false

    // ---- PaneContent --------------------------------------------------------

    override fun buildView(): View {
        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }
        paneRoot = pane
        showWidgetView()
        return pane
    }

    override fun onResumed() {
        if (!inConfigureView) startMonitor()
    }

    override fun onPaused() {
        stopMonitor()
    }

    override fun onDestroy() {
        stopMonitor()
    }

    // ---- Widget view --------------------------------------------------------

    private fun showWidgetView() {
        inConfigureView = false
        val pane = paneRoot ?: return
        pane.removeAllViews()

        // Reset all view references so the monitor knows which are active
        cpuView = null; memoryBarView = null; diskSpaceView = null
        diskIoView = null; networkIoView = null; signalView = null

        val selectedWidgets = loadSelectedWidgets()

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
        }

        if (selectedWidgets.isEmpty()) {
            inner.addView(TextView(ctx).apply {
                text = "No widgets selected.\nTap Configure to add widgets."
                textSize = 13f
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(16.dp(), 40.dp(), 16.dp(), 40.dp())
            })
        } else {
            selectedWidgets.forEach { widget ->
                inner.addView(sectionHeader(widget.label))
                val v: View = when (widget) {
                    Widget.CPU        -> CpuCoresView(ctx).also { cpuView = it }
                    Widget.MEMORY     -> MemoryBarView(ctx).also { memoryBarView = it }
                    Widget.DISK_SPACE -> DiskSpaceView(ctx).also { diskSpaceView = it }
                    Widget.DISK_IO    -> DiskIoView(ctx).also { diskIoView = it }
                    Widget.NETWORK    -> NetworkIoView(ctx).also { networkIoView = it }
                    Widget.SIGNAL     -> SignalView(ctx).also { signalView = it }
                }
                inner.addView(v.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                inner.addView(spacer())
            }
        }

        pane.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
            addView(inner)
        })
        pane.addView(configureButton())

        startMonitor()
    }

    // ---- Configure view -----------------------------------------------------

    private fun showConfigureView() {
        inConfigureView = true
        stopMonitor()
        val pane = paneRoot ?: return
        pane.removeAllViews()

        val selectedIds = loadSelectedWidgets().map { it.id }.toSet()
        val checkedState = mutableMapOf<String, Boolean>()
        Widget.entries.forEach { checkedState[it.id] = it.id in selectedIds }

        val titleEdit = EditText(ctx).apply {
            setText(bridge.getStringPref("${paneId}_title", "Widgets"))
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Theme.textSecondary)
            hint = "Pane title"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(Theme.inactiveBg)
            }
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dp()
                leftMargin = 10.dp()
                rightMargin = 10.dp()
                bottomMargin = 6.dp()
            }
            setTypeface(null, Typeface.BOLD)
        }

        val rowContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        Widget.entries.forEach { widget ->
            val checkbox = makeCheckbox(checkedState[widget.id] == true)
            rowContainer.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 2.dp() }
                addView(checkbox)
                addView(TextView(ctx).apply {
                    text = widget.label; textSize = 14f; setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                setOnClickListener {
                    val nowChecked = !(checkedState[widget.id] ?: false)
                    checkedState[widget.id] = nowChecked
                    updateCheckbox(checkbox, nowChecked)
                }
            })
        }

        pane.addView(titleEdit)
        pane.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rowContainer)
        })
        pane.addView(saveButton {
            val newTitle = titleEdit.text.toString().trim().ifBlank { "Widgets" }
            bridge.putStringPref("${paneId}_title", newTitle)
            saveSelectedWidgets(Widget.entries.filter { checkedState[it.id] == true })
            showWidgetView()
        })
    }

    // ---- UI helpers ---------------------------------------------------------

    private fun sectionHeader(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 9f
        setTextColor(Theme.primary)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.2f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4.dp() }
    }

    private fun spacer() = Space(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8.dp())
    }

    private fun configureButton() = TextView(ctx).apply {
        text = "Configure"
        textSize = 16f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(Theme.textSecondary)
        background = actionButtonBg(Theme.inactiveBg, ctx)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8.dp(); leftMargin = 10.dp(); rightMargin = 10.dp(); bottomMargin = 10.dp() }
        setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
        isClickable = true
        setOnClickListener { showConfigureView() }
    }

    private fun saveButton(onSave: () -> Unit) = TextView(ctx).apply {
        text = "Save"
        textSize = 16f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = actionButtonBg(Theme.primary, ctx)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8.dp(); leftMargin = 10.dp(); rightMargin = 10.dp(); bottomMargin = 10.dp() }
        setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
        isClickable = true
        setOnClickListener { onSave() }
    }

    private fun makeCheckbox(checked: Boolean) = TextView(ctx).apply {
        val size = 24.dp()
        layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = 12.dp() }
        gravity = Gravity.CENTER
        text = if (checked) "✓" else ""
        textSize = 14f
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4.dp().toFloat()
            if (checked) setColor(Theme.primary)
            else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
        }
    }

    private fun updateCheckbox(cb: TextView, checked: Boolean) {
        cb.text = if (checked) "✓" else ""
        cb.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4.dp().toFloat()
            if (checked) setColor(Theme.primary)
            else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
        }
    }

    // ---- Monitor ------------------------------------------------------------

    private fun startMonitor() {
        if (monitor == null) monitor = WidgetMonitor().also { it.start() }
    }

    private fun stopMonitor() {
        monitor?.stop()
        monitor = null
        diskIoView?.maxSeen = 1f
        networkIoView?.maxSeen = 1f
    }

    private inner class WidgetMonitor {

        private val handler = Handler(Looper.getMainLooper())

        // CPU
        private var prevCpuStats: Array<LongArray>? = null

        // Disk I/O
        private var prevDiskRead = 0L
        private var prevDiskWrite = 0L
        private var prevDiskTimeMs = 0L
        private var diskDevice: String? = null

        // Network I/O
        private var prevNetRxBytes = 0L
        private var prevNetTxBytes = 0L
        private var prevNetTimeMs = 0L

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
                        cpuResult?.let { cpuView?.update(it); cpuView?.requestLayout() }
                        memResult?.let { (u, c, t) -> memoryBarView?.update(u, c, t) }
                        diskSpResult?.let { (u, t) -> diskSpaceView?.update(u, t) }
                        diskIoResult?.let { (r, w) -> diskIoView?.update(r, w) }
                        netResult?.let { (rx, tx) -> networkIoView?.update(rx, tx) }
                        signalResult?.let { (wifi, mobile) -> signalView?.update(wifi, mobile) }
                    }
                }.start()
                handler.postDelayed(this, 1000L)
            }
        }

        fun start() { handler.post(poller) }
        fun stop()  { handler.removeCallbacks(poller) }

        private fun readCpuUsages(): FloatArray? {
            return try {
                val lines = BufferedReader(FileReader("/proc/stat")).use { it.readLines() }
                val coreLines = lines.filter { it.matches(Regex("cpu\\d+.*")) }
                if (coreLines.isEmpty()) return null
                val currentStats = Array(coreLines.size) { i ->
                    val parts = coreLines[i].trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }
                    LongArray(8) { j -> parts.getOrElse(j) { 0L } }
                }
                val prev = prevCpuStats
                prevCpuStats = currentStats
                if (prev == null || prev.size != currentStats.size) return null
                FloatArray(currentStats.size) { i ->
                    val cur = currentStats[i]; val prv = prev[i]
                    val dTotal = cur.sum() - prv.sum()
                    val dIdle  = (cur[3] + cur[4]) - (prv[3] + prv[4])
                    if (dTotal <= 0L) 0f else (1f - dIdle.toFloat() / dTotal).coerceIn(0f, 1f)
                }
            } catch (_: Exception) { null }
        }

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

        private fun readDiskSpace(): Pair<Long, Long>? {
            return try {
                val stat  = StatFs(Environment.getDataDirectory().path)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free  = stat.availableBlocksLong * stat.blockSizeLong
                Pair((total - free).coerceAtLeast(0L), total)
            } catch (_: Exception) { null }
        }

        private fun readDiskIO(): Pair<Float, Float>? {
            return try {
                val lines  = BufferedReader(FileReader("/proc/diskstats")).use { it.readLines() }
                val parsed = lines.map { it.trim().split(Regex("\\s+")) }

                if (diskDevice == null) {
                    fun ioScore(parts: List<String>) =
                        (parts.getOrNull(5)?.toLongOrNull() ?: 0L) +
                        (parts.getOrNull(9)?.toLongOrNull() ?: 0L)
                    val candidates = parsed.filter { parts ->
                        parts.size >= 14 &&
                        (parts[2].matches(Regex("sd[a-z]"))       ||
                         parts[2].matches(Regex("mmcblk\\d+"))    ||
                         parts[2].matches(Regex("nvme\\d+n\\d+")) ||
                         parts[2].matches(Regex("vd[a-z]"))       ||
                         parts[2].matches(Regex("dm-\\d+")))
                    }
                    diskDevice = candidates.maxByOrNull { ioScore(it) }?.getOrNull(2)
                    if (diskDevice == null) {
                        val partCandidates = parsed.filter { parts ->
                            parts.size >= 14 &&
                            (parts[2].matches(Regex("sd[a-z]\\d+"))        ||
                             parts[2].matches(Regex("mmcblk\\d+p\\d+"))    ||
                             parts[2].matches(Regex("nvme\\d+n\\d+p\\d+")) ||
                             parts[2].matches(Regex("vd[a-z]\\d+")))
                        }
                        diskDevice = partCandidates.maxByOrNull { ioScore(it) }?.getOrNull(2)
                    }
                }

                val device = diskDevice ?: return null
                val parts  = parsed.firstOrNull { it.getOrNull(2) == device } ?: return null
                val sectorsRead  = parts.getOrNull(5)?.toLongOrNull() ?: return null
                val sectorsWrite = parts.getOrNull(9)?.toLongOrNull() ?: return null
                val nowMs = System.currentTimeMillis()

                val prevRead = prevDiskRead; val prevWrite = prevDiskWrite; val prevTime = prevDiskTimeMs
                prevDiskRead = sectorsRead; prevDiskWrite = sectorsWrite; prevDiskTimeMs = nowMs
                if (prevTime == 0L) return Pair(0f, 0f)
                val elapsedSec = (nowMs - prevTime) / 1000f
                if (elapsedSec <= 0f) return Pair(0f, 0f)
                Pair(
                    ((sectorsRead  - prevRead)  * 512L / 1_000_000f / elapsedSec).coerceAtLeast(0f),
                    ((sectorsWrite - prevWrite) * 512L / 1_000_000f / elapsedSec).coerceAtLeast(0f)
                )
            } catch (_: Exception) { null }
        }

        private fun readNetworkIO(): Pair<Float, Float>? {
            return try {
                val totalRx = TrafficStats.getTotalRxBytes()
                val totalTx = TrafficStats.getTotalTxBytes()
                if (totalRx < 0 || totalTx < 0) return null
                val nowMs = System.currentTimeMillis()
                val prevRx = prevNetRxBytes; val prevTx = prevNetTxBytes; val prevTime = prevNetTimeMs
                prevNetRxBytes = totalRx; prevNetTxBytes = totalTx; prevNetTimeMs = nowMs
                if (prevTime == 0L) return Pair(0f, 0f)
                val elapsedSec = (nowMs - prevTime) / 1000f
                if (elapsedSec <= 0f) return Pair(0f, 0f)
                Pair(
                    ((totalRx - prevRx).coerceAtLeast(0L) / 1024f / elapsedSec),
                    ((totalTx - prevTx).coerceAtLeast(0L) / 1024f / elapsedSec)
                )
            } catch (_: Exception) { null }
        }

        private fun readSignalStrengths(): Pair<Int?, Int?> {
            val wifiRssi: Int? = try {
                @Suppress("DEPRECATION")
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val rssi = wm?.connectionInfo?.rssi
                if (rssi == null || rssi == Int.MIN_VALUE || rssi <= -127) null else rssi
            } catch (_: Exception) { null }

            val mobileDbm: Int? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    tm?.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
                        ?.takeIf { it > Int.MIN_VALUE && it > -200 }
                } else null
            } catch (_: SecurityException) { null } catch (_: Exception) { null }

            return Pair(wifiRssi, mobileDbm)
        }
    }

    // ---- Prefs helpers ------------------------------------------------------

    private fun loadSelectedWidgets(): List<Widget> {
        val raw = bridge.getStringPref("${paneId}_widgets", null)
        if (raw == null || raw.isBlank()) return Widget.entries.toList()
        return raw.split(",").mapNotNull { id -> Widget.entries.find { it.id == id } }
    }

    private fun saveSelectedWidgets(widgets: List<Widget>) {
        bridge.putStringPref("${paneId}_widgets", widgets.joinToString(",") { it.id })
    }
}
