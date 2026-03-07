package com.androbuttons

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

// ---------------------------------------------------------------------------
// LoadHistogramView
//
// Scrolling bar histogram of system load average (1-min) over time.
// Fixed height of 100dp. Y-axis scales to numCores so a fully-loaded system
// reaches 100%. Colour transitions amber → orange → red at 60% / 85%.
// Newest sample is on the right; older samples scroll left.
// A faint horizontal line marks the "1 core" load level.
// Current load value is printed top-right.
// ---------------------------------------------------------------------------
class LoadHistogramView(context: Context) : View(context) {

    private val HISTORY_SIZE = 120          // 2 minutes at 1 sample/s
    private val HEIGHT_DP    = 100f
    private val BAR_GAP_DP   = 1f
    private val PAD_DP       = 6f

    private val history = ArrayDeque<Float>(HISTORY_SIZE)
    var numCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val barPaintAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.FILL
    }
    private val barPaintOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.FILL
    }
    private val barPaintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#404040")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    fun update(load1min: Float) {
        if (history.size >= HISTORY_SIZE) history.removeFirst()
        history.addLast(load1min)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(HEIGHT_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w    = width.toFloat()
        val h    = height.toFloat()
        val pad  = dp(PAD_DP)
        val gap  = dp(BAR_GAP_DP)
        val plotH = h - pad * 2f
        val plotTop = pad
        val plotBot = h - pad

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, dp(6f), dp(6f), bgPaint)

        if (history.isEmpty()) return

        val maxLoad = numCores.toFloat()

        // Dashed grid lines at each integer core boundary within range
        for (c in 1..numCores) {
            val y = plotBot - (c.toFloat() / maxLoad) * plotH
            if (y >= plotTop) canvas.drawLine(pad, y, w - pad, y, axisPaint)
        }

        // Bars — fit all history into available width
        val n         = history.size
        val totalGap  = gap * (n - 1)
        val barW      = ((w - pad * 2f - totalGap) / n).coerceAtLeast(1f)

        history.forEachIndexed { idx, load ->
            val fraction  = (load / maxLoad).coerceIn(0f, 1f)
            val barHeight = fraction * plotH
            val left      = pad + idx * (barW + gap)
            val right     = left + barW
            val top       = plotBot - barHeight
            val barPaint  = when {
                fraction < 0.60f -> barPaintAmber
                fraction < 0.85f -> barPaintOrange
                else             -> barPaintRed
            }
            canvas.drawRect(left, top, right, plotBot, barPaint)
        }

        // Current value label (top-right)
        val latest = history.last()
        canvas.drawText("%.2f".format(latest), w - dp(4f), pad + labelPaint.textSize, labelPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// MemoryBarView
//
// Multi-colour stacked horizontal bar: used (orange) | cached (amber) | free (track).
// Labels below each segment showing MB values.
// ---------------------------------------------------------------------------
class MemoryBarView(context: Context) : View(context) {

    private var usedMb:   Long = 0L
    private var cachedMb: Long = 0L
    private var totalMb:  Long = 1L   // avoid div/0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.FILL
    }
    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.FILL
    }
    private val cachedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(8f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val usedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val cachedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        textSize = sp(8f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val freeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
    }

    fun update(usedMb: Long, cachedMb: Long, totalMb: Long) {
        this.usedMb   = usedMb
        this.cachedMb = cachedMb
        this.totalMb  = totalMb.coerceAtLeast(1L)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(56f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w   = width.toFloat()
        val h   = height.toFloat()
        val pad = dp(8f)
        val barH = dp(16f)
        val barTop = pad
        val barBot = barTop + barH
        val barLeft  = pad
        val barRight = w - pad
        val barW = barRight - barLeft
        val r = barH / 2f

        canvas.drawRoundRect(0f, 0f, w, h, dp(6f), dp(6f), bgPaint)

        val usedFrac   = (usedMb.toFloat()   / totalMb).coerceIn(0f, 1f)
        val cachedFrac = (cachedMb.toFloat()  / totalMb).coerceIn(0f, 1f - usedFrac)

        val usedRight   = barLeft + barW * usedFrac
        val cachedRight = usedRight + barW * cachedFrac

        // Full track (represents "free" area)
        canvas.drawRoundRect(barLeft, barTop, barRight, barBot, r, r, trackPaint)

        // Cached segment
        if (cachedFrac > 0f) {
            canvas.save()
            canvas.clipRect(barLeft, barTop, cachedRight, barBot)
            canvas.drawRoundRect(barLeft, barTop, barRight, barBot, r, r, cachedPaint)
            canvas.restore()
        }

        // Used segment
        if (usedFrac > 0f) {
            canvas.save()
            canvas.clipRect(barLeft, barTop, usedRight, barBot)
            canvas.drawRoundRect(barLeft, barTop, barRight, barBot, r, r, usedPaint)
            canvas.restore()
        }

        // Labels below bar
        val labelY = barBot + dp(4f) - labelPaint.ascent()
        val freeMb = totalMb - usedMb - cachedMb

        canvas.drawText("USED ${usedMb}MB", barLeft, labelY, usedLabelPaint)
        canvas.drawText("CACHE ${cachedMb}MB", w / 2f, labelY, cachedLabelPaint)
        canvas.drawText("FREE ${freeMb}MB", barRight, labelY, freeLabelPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// DiskSpaceView
//
// Single stacked bar: used (orange) | free (track).
// Shows internal storage utilisation via StatFs data passed in from the pane.
// ---------------------------------------------------------------------------
class DiskSpaceView(context: Context) : View(context) {

    private var usedBytes:  Long = 0L
    private var totalBytes: Long = 1L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.FILL
    }
    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.FILL
    }
    private val usedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val pctLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = sp(8f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val freeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
    }

    fun update(usedBytes: Long, totalBytes: Long) {
        this.usedBytes  = usedBytes
        this.totalBytes = totalBytes.coerceAtLeast(1L)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(56f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w    = width.toFloat()
        val pad  = dp(8f)
        val barH = dp(16f)
        val barTop   = pad
        val barBot   = barTop + barH
        val barLeft  = pad
        val barRight = w - pad
        val barW = barRight - barLeft
        val r = barH / 2f

        canvas.drawRoundRect(0f, 0f, w, height.toFloat(), dp(6f), dp(6f), bgPaint)

        val usedFrac = (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        val usedRight = barLeft + barW * usedFrac

        canvas.drawRoundRect(barLeft, barTop, barRight, barBot, r, r, trackPaint)

        if (usedFrac > 0f) {
            canvas.save()
            canvas.clipRect(barLeft, barTop, usedRight, barBot)
            canvas.drawRoundRect(barLeft, barTop, barRight, barBot, r, r, usedPaint)
            canvas.restore()
        }

        val labelY   = barBot + dp(4f) - usedLabelPaint.ascent()
        val usedGb   = usedBytes  / (1024L * 1024L * 1024L)
        val freeGb   = (totalBytes - usedBytes) / (1024L * 1024L * 1024L)
        val pctUsed  = (usedFrac * 100f).toInt()

        canvas.drawText("USED ${usedGb}GB", barLeft, labelY, usedLabelPaint)
        canvas.drawText("${pctUsed}%", w / 2f, labelY, pctLabelPaint)
        canvas.drawText("FREE ${freeGb}GB", barRight, labelY, freeLabelPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// DiskIoView
//
// Two horizontal bars: READ (blue) and WRITE (red).
// Dynamic max auto-expands; floor is 1 MB/s.
// ---------------------------------------------------------------------------
class DiskIoView(context: Context) : View(context) {

    private var readMBps:  Float = 0f
    private var writeMBps: Float = 0f
    var maxSeen: Float = 1f   // resets externally when pane is paused

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.FILL
    }
    private val readPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        style = Paint.Style.FILL
    }
    private val writePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF5350")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val readLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val writeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF5350")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
    }

    fun update(readMBps: Float, writeMBps: Float) {
        this.readMBps  = readMBps
        this.writeMBps = writeMBps
        maxSeen = maxOf(maxSeen, readMBps, writeMBps, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(72f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()

        canvas.drawRoundRect(0f, 0f, w, height.toFloat(), dp(6f), dp(6f), bgPaint)

        val pad     = dp(8f)
        val barH    = dp(12f)
        val lblW    = dp(36f)
        val valW    = dp(40f)
        val gap     = dp(8f)
        val barLeft  = pad + lblW + dp(4f)
        val barRight = w - pad - valW

        fun drawRow(top: Float, label: String, mbps: Float, fillPaint: Paint, lp: Paint) {
            val bot = top + barH
            val r = barH / 2f
            val fraction = (mbps / maxSeen).coerceIn(0f, 1f)

            val textY = top + barH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, pad + lblW, textY, lp)

            canvas.drawRoundRect(barLeft, top, barRight, bot, r, r, trackPaint)
            if (fraction > 0f) {
                val fillRight = barLeft + (barRight - barLeft) * fraction
                canvas.drawRoundRect(barLeft, top, fillRight, bot, r, r, fillPaint)
            }

            val valText = if (mbps >= 1f) "%.1fMB/s".format(mbps) else "%.0fkB/s".format(mbps * 1024f)
            canvas.drawText(valText, barRight + dp(4f), textY, valuePaint)
        }

        drawRow(pad,              "READ",  readMBps,  readPaint,  readLabelPaint)
        drawRow(pad + barH + gap, "WRITE", writeMBps, writePaint, writeLabelPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// NetworkIoView
//
// Two horizontal bars: RX (green) and TX (cyan).
// Dynamic max auto-expands; floor is 1 kB/s.
// ---------------------------------------------------------------------------
class NetworkIoView(context: Context) : View(context) {

    private var rxKBps: Float = 0f
    private var txKBps: Float = 0f
    var maxSeen: Float = 1f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.FILL
    }
    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66BB6A")
        style = Paint.Style.FILL
    }
    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26C6DA")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val rxLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66BB6A")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val txLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26C6DA")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
    }

    fun update(rxKBps: Float, txKBps: Float) {
        this.rxKBps = rxKBps
        this.txKBps = txKBps
        maxSeen = maxOf(maxSeen, rxKBps, txKBps, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(72f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()

        canvas.drawRoundRect(0f, 0f, w, height.toFloat(), dp(6f), dp(6f), bgPaint)

        val pad      = dp(8f)
        val barH     = dp(12f)
        val lblW     = dp(36f)
        val valW     = dp(56f)
        val gap      = dp(8f)
        val barLeft  = pad + lblW + dp(4f)
        val barRight = w - pad - valW

        fun formatRate(kbps: Float): String = when {
            kbps >= 1024f -> "%.1fMB/s".format(kbps / 1024f)
            else          -> "%.0fkB/s".format(kbps)
        }

        fun drawRow(top: Float, label: String, kbps: Float, fillPaint: Paint, lp: Paint) {
            val bot = top + barH
            val r = barH / 2f
            val fraction = (kbps / maxSeen).coerceIn(0f, 1f)

            val textY = top + barH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, pad + lblW, textY, lp)

            canvas.drawRoundRect(barLeft, top, barRight, bot, r, r, trackPaint)
            if (fraction > 0f) {
                canvas.drawRoundRect(barLeft, top, barLeft + (barRight - barLeft) * fraction, bot, r, r, fillPaint)
            }

            canvas.drawText(formatRate(kbps), barRight + dp(4f), textY, valuePaint)
        }

        drawRow(pad,              "RX", rxKBps, rxPaint, rxLabelPaint)
        drawRow(pad + barH + gap, "TX", txKBps, txPaint, txLabelPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// SignalView
//
// Two horizontal bars: WiFi signal (blue) and Mobile signal (green).
// dBm values are normalised to a 0–1 fill fraction.
// Null values (disconnected / no permission) render as an empty greyed bar.
// ---------------------------------------------------------------------------
class SignalView(context: Context) : View(context) {

    private var wifiRssi:  Int? = null   // dBm, typically −100…−30
    private var mobileDbm: Int? = null   // dBm, typically −120…−50

    private val WIFI_MIN   = -100f
    private val WIFI_MAX   = -30f
    private val MOBILE_MIN = -120f
    private val MOBILE_MAX = -50f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.FILL
    }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val wifiLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val mobileLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66BB6A")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val offValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#505050")
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
    }

    fun update(wifiRssi: Int?, mobileDbm: Int?) {
        this.wifiRssi  = wifiRssi
        this.mobileDbm = mobileDbm
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(72f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()

        canvas.drawRoundRect(0f, 0f, w, height.toFloat(), dp(6f), dp(6f), bgPaint)

        val pad      = dp(8f)
        val barH     = dp(12f)
        val lblW     = dp(36f)
        val valW     = dp(52f)
        val gap      = dp(8f)
        val barLeft  = pad + lblW + dp(4f)
        val barRight = w - pad - valW

        fun fraction(dbm: Int, minDbm: Float, maxDbm: Float): Float =
            ((dbm - minDbm) / (maxDbm - minDbm)).coerceIn(0f, 1f)

        fun drawRow(top: Float, rowLabel: String, dbm: Int?, minDbm: Float, maxDbm: Float,
                    lp: Paint, offText: String) {
            val bot = top + barH
            val r = barH / 2f
            val textY = top + barH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f

            canvas.drawText(rowLabel, pad + lblW, textY, lp)
            canvas.drawRoundRect(barLeft, top, barRight, bot, r, r, trackPaint)

            if (dbm != null) {
                val color = signalColor(dbm)
                val frac  = fraction(dbm, minDbm, maxDbm)
                if (frac > 0f) {
                    barFillPaint.color = color
                    canvas.drawRoundRect(barLeft, top, barLeft + (barRight - barLeft) * frac, bot, r, r, barFillPaint)
                }
                valuePaint.color = color
                canvas.drawText("${dbm}dBm", barRight + dp(4f), textY, valuePaint)
            } else {
                canvas.drawText(offText, barRight + dp(4f), textY, offValuePaint)
            }
        }

        drawRow(pad,              "WiFi",   wifiRssi,  WIFI_MIN,   WIFI_MAX,   wifiLabelPaint,   "OFF")
        drawRow(pad + barH + gap, "Mobile", mobileDbm, MOBILE_MIN, MOBILE_MAX, mobileLabelPaint, "NO SIG")
    }

    // Color stops: ≥-40 light-green · -80 dark-green · -95 orange · ≤-100 red
    private fun signalColor(dbm: Int): Int {
        val lightGreen = Color.parseColor("#81C784")
        val darkGreen  = Color.parseColor("#388E3C")
        val orange     = Color.parseColor("#F57C00")
        val red        = Color.parseColor("#F44336")
        return when {
            dbm >= -40  -> lightGreen
            dbm >= -80  -> lerpColor(lightGreen, darkGreen, (-dbm - 40f) / 40f)
            dbm >= -95  -> lerpColor(darkGreen,  orange,    (-dbm - 80f) / 15f)
            dbm >= -100 -> lerpColor(orange,     red,       (-dbm - 95f) / 5f)
            else        -> red
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a)   + t * (Color.red(b)   - Color.red(a))).toInt(),
        (Color.green(a) + t * (Color.green(b) - Color.green(a))).toInt(),
        (Color.blue(a)  + t * (Color.blue(b)  - Color.blue(a))).toInt()
    )

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}
