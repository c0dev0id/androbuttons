package com.androbuttons

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.View

// ---------------------------------------------------------------------------
// CompassView
//
// The needle is a fixed white arrow always pointing straight up.
// The outer frame (circle + N E S W labels) rotates by -azimuth so that
// the N label always points toward magnetic north regardless of device heading.
// ---------------------------------------------------------------------------
class CompassView(context: Context) : View(context) {

    private var azimuthDeg: Float = 0f

    // colors matching OverlayService palette
    private val bgColor      = Color.parseColor("#FF1E1E1E")
    private val primaryColor = Color.parseColor("#F57C00")    // orange – N label
    private val ringColor    = Color.parseColor("#B0B0B0")    // secondaryText
    private val needleColor  = Color.WHITE

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = needleColor
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val nTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        textSize = sp(10f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
    }

    fun setAzimuth(degrees: Float) {
        azimuthDeg = degrees
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)   // square
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) - dp(6f)

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), bgPaint)

        // --- Rotating frame ---
        canvas.save()
        canvas.rotate(-azimuthDeg, cx, cy)

        // Circle
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Cardinal labels (N at top = -radius from center)
        val lblOffset = radius - dp(10f)
        val textVCenter = (nTextPaint.descent() + nTextPaint.ascent()) / 2f

        // N (top)
        canvas.drawText("N", cx, cy - lblOffset - textVCenter, nTextPaint)
        // S (bottom)
        canvas.drawText("S", cx, cy + lblOffset - textVCenter, cardinalPaint)
        // W (left)
        canvas.drawText("W", cx - lblOffset, cy - textVCenter, cardinalPaint)
        // E (right)
        canvas.drawText("E", cx + lblOffset, cy - textVCenter, cardinalPaint)

        // Tick marks at 45° intervals
        val tickOuter = radius
        val tickInner = radius - dp(4f)
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val cosA = Math.cos(angle).toFloat()
            val sinA = Math.sin(angle).toFloat()
            canvas.drawLine(
                cx + sinA * tickInner, cy - cosA * tickInner,
                cx + sinA * tickOuter, cy - cosA * tickOuter,
                ringPaint
            )
        }

        canvas.restore()

        // --- Static needle (always pointing up) ---
        val needleLength = radius - dp(14f)
        val needleWidth  = dp(2.5f)

        // Shaft
        canvas.drawLine(cx, cy, cx, cy - needleLength, needlePaint)

        // Arrowhead (north tip)
        val arrowPath = Path().apply {
            moveTo(cx, cy - needleLength)
            lineTo(cx - needleWidth * 1.5f, cy - needleLength + dp(8f))
            lineTo(cx + needleWidth * 1.5f, cy - needleLength + dp(8f))
            close()
        }
        canvas.drawPath(arrowPath, needlePaint)

        // Small circle at pivot
        val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, dp(3f), pivotPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// SpeedometerView
//
// A 60dp-tall horizontal bar with a green→red gradient.
// The bar is clipped to represent current speed; first 2dp always visible.
// Speed shown in bold white text centred on the bar.
// ---------------------------------------------------------------------------
class SpeedometerView(context: Context) : View(context) {

    private var speedKmh: Float = 0f

    private val MAX_SPEED = 170f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1E1E1E")
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textSize = sp(18f)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
    }

    fun setSpeedKmh(speed: Float) {
        speedKmh = speed.coerceAtLeast(0f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = dp(60f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, dp(4f), dp(4f), bgPaint)

        // Gradient shader (recreate each draw since width is known now)
        barPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(Color.parseColor("#4CAF50"), Color.parseColor("#F44336")),
            null,
            Shader.TileMode.CLAMP
        )

        // Bar width: minimum 2dp, scales to full width at 170 km/h
        val fraction = (speedKmh / MAX_SPEED).coerceIn(0f, 1f)
        val minPx = dp(2f)
        val barW = (minPx + fraction * (w - minPx)).coerceAtMost(w)

        canvas.save()
        canvas.clipRect(0f, 0f, barW, h)
        canvas.drawRoundRect(0f, 0f, w, h, dp(4f), dp(4f), barPaint)
        canvas.restore()

        // Speed text
        val cy = h / 2f
        val textVCenter = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("%.0f".format(speedKmh), w / 2f, cy - textVCenter - dp(5f), textPaint)
        canvas.drawText("km/h", w / 2f, cy - textVCenter + dp(14f), unitPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// LeanAngleView
//
// Two bars from the centre outward (left bar for left lean, right for right).
// Green at the centre → red at the edges. Full width at ±45°.
// Lean angle shown in bold white text centred on the view.
// ---------------------------------------------------------------------------
class LeanAngleView(context: Context) : View(context) {

    private var leanDeg: Float = 0f

    private val MAX_LEAN = 45f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1E1E1E")
        style = Paint.Style.FILL
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val leftBarPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rightBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textSize = sp(16f)
        textAlign = Paint.Align.CENTER
    }

    fun setLeanDegrees(degrees: Float) {
        leanDeg = degrees
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(50f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f

        canvas.drawRoundRect(0f, 0f, w, h, dp(4f), dp(4f), bgPaint)

        val half = w / 2f

        if (leanDeg < 0f) {
            // Leaning left → bar from centre extending left
            val fraction = (-leanDeg / MAX_LEAN).coerceIn(0f, 1f)
            val barW = fraction * half
            leftBarPaint.shader = LinearGradient(
                cx, 0f, cx - half, 0f,
                intArrayOf(Color.parseColor("#4CAF50"), Color.parseColor("#F44336")),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipRect(cx - barW, 0f, cx, h)
            canvas.drawRect(cx - half, 0f, cx, h, leftBarPaint)
            canvas.restore()
        } else if (leanDeg > 0f) {
            // Leaning right → bar from centre extending right
            val fraction = (leanDeg / MAX_LEAN).coerceIn(0f, 1f)
            val barW = fraction * half
            rightBarPaint.shader = LinearGradient(
                cx, 0f, cx + half, 0f,
                intArrayOf(Color.parseColor("#4CAF50"), Color.parseColor("#F44336")),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipRect(cx, 0f, cx + barW, h)
            canvas.drawRect(cx, 0f, cx + half, h, rightBarPaint)
            canvas.restore()
        }

        // Centre line
        canvas.drawLine(cx, 0f, cx, h, centerLinePaint)

        // Text
        val textVCenter = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("%.1f°".format(leanDeg), cx, h / 2f - textVCenter, textPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// ForceDisplayView
//
// Square area. Dark background with subtle dot grid pattern.
// A red dot moves according to linear acceleration: 1G = edge of the area.
// The dot leaves a red trail that fades over 6 seconds.
// ---------------------------------------------------------------------------
class ForceDisplayView(context: Context) : View(context) {

    private val G = 9.81f
    private val TRAIL_DURATION_MS = 6000L

    // Trail point: screen x, y, timestamp
    private data class TrailPoint(val x: Float, val y: Float, val timeMs: Long)

    private val trail = ArrayDeque<TrailPoint>()
    private var dotX = 0f
    private var dotY = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#121212")
        style = Paint.Style.FILL
    }
    private val dotGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E1E1E")
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC2222")
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
    }

    fun setForce(ax: Float, ay: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) {
            invalidate()
            return
        }
        val cx = w / 2f
        val cy = h / 2f
        val dotRadius = dp(5f)

        dotX = (cx + (ax / G) * cx).coerceIn(dotRadius, w - dotRadius)
        dotY = (cy - (ay / G) * cy).coerceIn(dotRadius, h - dotRadius)

        val now = SystemClock.uptimeMillis()
        trail.addLast(TrailPoint(dotX, dotY, now))
        // Prune old trail points
        while (trail.isNotEmpty() && now - trail.first().timeMs > TRAIL_DURATION_MS) {
            trail.removeFirst()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)   // square
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Dot grid
        val gridSpacing = dp(12f)
        val dotR = dp(1.5f)
        var gx = gridSpacing / 2f
        while (gx < w) {
            var gy = gridSpacing / 2f
            while (gy < h) {
                canvas.drawCircle(gx, gy, dotR, dotGridPaint)
                gy += gridSpacing
            }
            gx += gridSpacing
        }

        // Trail
        val now = SystemClock.uptimeMillis()
        if (trail.size >= 2) {
            for (i in 1 until trail.size) {
                val prev = trail[i - 1]
                val curr = trail[i]
                val age = now - prev.timeMs
                val alpha = (255f * (1f - age.toFloat() / TRAIL_DURATION_MS)).toInt().coerceIn(0, 255)
                trailPaint.alpha = alpha
                canvas.drawLine(prev.x, prev.y, curr.x, curr.y, trailPaint)
            }
        }

        // Dot (use dotX/dotY if set, else centre)
        val dx = if (width > 0) dotX else cx
        val dy = if (height > 0) dotY else cy
        canvas.drawCircle(dx, dy, dp(5f), dotPaint)

        // Schedule next invalidate if trail is still active (for fade animation)
        if (trail.isNotEmpty()) {
            postInvalidateDelayed(50)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )
}
