package com.androbuttons

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

// ---------------------------------------------------------------------------
// CpuCoresView
//
// Horizontal bar for each CPU core, dynamically sized by core count.
// Bar fill transitions amber → orange → red at 60% / 85% load.
// ---------------------------------------------------------------------------
class CpuCoresView(context: Context) : View(context) {

    private var coreUsages: FloatArray = FloatArray(0)

    private val BAR_HEIGHT_DP = 12f
    private val BAR_GAP_DP   = 6f
    private val LABEL_W_DP   = 28f
    private val PAD_DP       = 8f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.FILL
    }
    private val fillPaintAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.FILL
    }
    private val fillPaintOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.FILL
    }
    private val fillPaintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(8f)
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0B0B0")
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.MONOSPACE
    }

    fun update(usages: FloatArray) {
        coreUsages = usages
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = coreUsages.size.coerceAtLeast(1)
        val h = (PAD_DP * 2 + n * BAR_HEIGHT_DP + (n - 1) * BAR_GAP_DP)
        setMeasuredDimension(w, dp(h).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad  = dp(PAD_DP)
        val barH = dp(BAR_HEIGHT_DP)
        val gap  = dp(BAR_GAP_DP)
        val lblW = dp(LABEL_W_DP)

        canvas.drawRoundRect(0f, 0f, w, h, dp(6f), dp(6f), bgPaint)

        val barLeft  = pad + lblW + dp(4f)
        val barRight = w - pad - dp(36f)   // reserve right gutter for % value

        for (i in coreUsages.indices) {
            val fraction = coreUsages[i].coerceIn(0f, 1f)
            val top  = pad + i * (barH + gap)
            val bot  = top + barH
            val r    = barH / 2f

            // Label
            val textY = top + barH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText("C$i", pad + lblW, textY, labelPaint)

            // Track
            canvas.drawRoundRect(barLeft, top, barRight, bot, r, r, trackPaint)

            // Fill
            if (fraction > 0f) {
                val fillRight = barLeft + (barRight - barLeft) * fraction
                val fillPaint = when {
                    fraction < 0.60f -> fillPaintAmber
                    fraction < 0.85f -> fillPaintOrange
                    else             -> fillPaintRed
                }
                canvas.drawRoundRect(barLeft, top, fillRight, bot, r, r, fillPaint)
            }

            // Value
            canvas.drawText("%3d%%".format((fraction * 100).toInt()), barRight + dp(4f), textY, valuePaint)
        }
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

        // Cached segment: clip right edge flat, left edge gets rounded from underlying track
        if (cachedFrac > 0f) {
            canvas.save()
            canvas.clipRect(barLeft, barTop, cachedRight, barBot)
            canvas.drawRoundRect(barLeft, barTop, barRight, barBot, r, r, cachedPaint)
            canvas.restore()
        }

        // Used segment: clip right edge flat, left edge rounded
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
        val h = height.toFloat()

        canvas.drawRoundRect(0f, 0f, w, h, dp(6f), dp(6f), bgPaint)

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
