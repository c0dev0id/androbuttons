package com.androbuttons

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.View

// ---------------------------------------------------------------------------
// CompassView
//
// The needle is a fixed white arrow always pointing straight up.
// The outer frame (two concentric rings + dense tick marks + N E S W labels)
// rotates by -azimuth so the N label always points toward magnetic north.
// ---------------------------------------------------------------------------
class CompassView(context: Context) : View(context) {

    private var azimuthDeg: Float = 0f

    private val bgColor      = Color.parseColor("#0D0D0D")
    private val primaryColor = Color.parseColor("#F57C00")    // amber – N label + major ticks
    private val ringColor    = Color.parseColor("#3A3A3A")    // dark bezel
    private val needleColor  = Color.WHITE

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
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
        val outerRadius = radius + dp(3f)

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), bgPaint)

        // --- Rotating frame ---
        canvas.save()
        canvas.rotate(-azimuthDeg, cx, cy)

        // Outer bezel ring
        canvas.drawCircle(cx, cy, outerRadius, outerRingPaint)
        // Inner ring
        canvas.drawCircle(cx, cy, radius, innerRingPaint)

        // Dense tick marks every 10° (36 ticks total)
        for (i in 0 until 36) {
            val angleDeg = i * 10
            val isMajor = angleDeg % 30 == 0
            val tickLen = if (isMajor) dp(6f) else dp(3f)
            val paint = if (isMajor) majorTickPaint else minorTickPaint
            val tickOuter = radius
            val tickInner = radius - tickLen
            val angle = Math.toRadians(angleDeg.toDouble())
            val cosA = Math.cos(angle).toFloat()
            val sinA = Math.sin(angle).toFloat()
            canvas.drawLine(
                cx + sinA * tickInner, cy - cosA * tickInner,
                cx + sinA * tickOuter, cy - cosA * tickOuter,
                paint
            )
        }

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

        // Amber pivot circle
        val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, dp(4f), pivotPaint)
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
// Radial arc gauge: 260° sweep from 140°, amber→orange→red value arc,
// thin needle, large speed digits, tick marks.  Square view.
// ---------------------------------------------------------------------------
class SpeedometerView(context: Context) : View(context) {

    private var speedKmh: Float = 0f

    private val MAX_SPEED   = 170f
    private val START_ANGLE = 140f
    private val SWEEP_ANGLE = 260f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaintAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaintOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textSize = sp(28f)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }

    fun setSpeedKmh(speed: Float) {
        speedKmh = speed.coerceAtLeast(0f)
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
        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), bgPaint)

        val arcInset = dp(16f)
        val arcRadius = cx - arcInset
        val arcOval = RectF(arcInset, arcInset, w - arcInset, h - arcInset)

        // Track arc (full 260°)
        canvas.drawArc(arcOval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // Value arc
        val fraction = (speedKmh / MAX_SPEED).coerceIn(0f, 1f)
        val valueSweep = fraction * SWEEP_ANGLE
        if (valueSweep > 0f) {
            val valuePaint = when {
                fraction < 0.60f -> valuePaintAmber
                fraction < 0.85f -> valuePaintOrange
                else             -> valuePaintRed
            }
            canvas.drawArc(arcOval, START_ANGLE, valueSweep, false, valuePaint)
        }

        // Tick marks at every 10° of the 260° sweep (27 ticks: indices 0..26)
        for (i in 0..26) {
            val angleDeg = START_ANGLE + i * 10f
            val isMajor = i % 3 == 0
            val tickLen = if (isMajor) dp(8f) else dp(4f)
            val paint = if (isMajor) majorTickPaint else minorTickPaint
            val tickOuterR = arcRadius
            val tickInnerR = arcRadius - tickLen
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosA = Math.cos(rad).toFloat()
            val sinA = Math.sin(rad).toFloat()
            canvas.drawLine(
                cx + cosA * tickInnerR, cy + sinA * tickInnerR,
                cx + cosA * tickOuterR, cy + sinA * tickOuterR,
                paint
            )
        }

        // Needle
        val needleAngle = START_ANGLE + fraction * SWEEP_ANGLE
        val needleLength = arcRadius - dp(16f)
        val needleRad = Math.toRadians(needleAngle.toDouble())
        canvas.drawLine(
            cx, cy,
            cx + Math.cos(needleRad).toFloat() * needleLength,
            cy + Math.sin(needleRad).toFloat() * needleLength,
            needlePaint
        )

        // Speed text
        val textVCenter = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("%.0f".format(speedKmh), cx, cy - textVCenter - dp(6f), textPaint)

        // "km/h" label
        val unitVCenter = (unitPaint.descent() + unitPaint.ascent()) / 2f
        canvas.drawText("km/h", cx, cy - unitVCenter + dp(14f), unitPaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}

// ---------------------------------------------------------------------------
// AltimeterView
//
// Radial arc gauge: 260° sweep, 0–3000 m range.  Matches SpeedometerView
// visual style so the two instruments look like a matched pair.
// ---------------------------------------------------------------------------
class AltimeterView(context: Context) : View(context) {

    private var altitudeM: Float = 0f

    private val MAX_ALT    = 3000f
    private val START_ANGLE = 140f
    private val SWEEP_ANGLE = 260f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaintAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaintOrange = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textSize = sp(22f)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }

    fun setAltitudeM(alt: Float) {
        altitudeM = alt.coerceAtLeast(0f)
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

        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), bgPaint)

        val arcInset  = dp(16f)
        val arcRadius = cx - arcInset
        val arcOval   = RectF(arcInset, arcInset, w - arcInset, h - arcInset)

        canvas.drawArc(arcOval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val fraction   = (altitudeM / MAX_ALT).coerceIn(0f, 1f)
        val valueSweep = fraction * SWEEP_ANGLE
        if (valueSweep > 0f) {
            val valuePaint = when {
                fraction < 0.60f -> valuePaintAmber
                fraction < 0.85f -> valuePaintOrange
                else             -> valuePaintRed
            }
            canvas.drawArc(arcOval, START_ANGLE, valueSweep, false, valuePaint)
        }

        for (i in 0..26) {
            val angleDeg = START_ANGLE + i * 10f
            val isMajor  = i % 3 == 0
            val tickLen  = if (isMajor) dp(8f) else dp(4f)
            val paint    = if (isMajor) majorTickPaint else minorTickPaint
            val tickOuterR = arcRadius
            val tickInnerR = arcRadius - tickLen
            val rad  = Math.toRadians(angleDeg.toDouble())
            val cosA = Math.cos(rad).toFloat()
            val sinA = Math.sin(rad).toFloat()
            canvas.drawLine(
                cx + cosA * tickInnerR, cy + sinA * tickInnerR,
                cx + cosA * tickOuterR, cy + sinA * tickOuterR,
                paint
            )
        }

        val needleAngle  = START_ANGLE + fraction * SWEEP_ANGLE
        val needleLength = arcRadius - dp(16f)
        val needleRad    = Math.toRadians(needleAngle.toDouble())
        canvas.drawLine(
            cx, cy,
            cx + Math.cos(needleRad).toFloat() * needleLength,
            cy + Math.sin(needleRad).toFloat() * needleLength,
            needlePaint
        )

        val textVCenter = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("%.0f".format(altitudeM), cx, cy - textVCenter - dp(6f), textPaint)

        val unitVCenter = (unitPaint.descent() + unitPaint.ascent()) / 2f
        canvas.drawText("m", cx, cy - unitVCenter + dp(14f), unitPaint)
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
// Artificial horizon style: tilting amber bank bar + fixed wing icons.
// Height is 50dp; same public API.
// ---------------------------------------------------------------------------
class LeanAngleView(context: Context) : View(context) {

    private var leanDeg: Float = 0f

    private val MAX_LEAN = 45f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val tickMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val bankBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val wingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
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

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, dp(4f), dp(4f), bgPaint)

        // Horizon baseline
        canvas.drawLine(0f, h / 2f, w, h / 2f, horizonPaint)

        // Tick marks at ±15°, ±30°, ±45° (mapped to half-width)
        val tickAngles = floatArrayOf(-45f, -30f, -15f, 15f, 30f, 45f)
        for (angle in tickAngles) {
            val tickX = cx + (angle / MAX_LEAN) * (w / 2f)
            canvas.drawLine(tickX, h / 2f - dp(3f), tickX, h / 2f + dp(3f), tickMarkPaint)
        }

        // Bank bar: amber line rotated by leanDeg around the centre
        val barHalfW = w * 0.3f
        canvas.save()
        canvas.rotate(leanDeg, cx, h / 2f)
        canvas.drawLine(cx - barHalfW, h / 2f, cx + barHalfW, h / 2f, bankBarPaint)
        canvas.restore()

        // Fixed wing icons (do NOT rotate)
        val wingLen = dp(10f)
        val wingY = h / 2f
        canvas.drawLine(cx - dp(18f) - wingLen, wingY, cx - dp(18f) + wingLen, wingY, wingPaint)
        canvas.drawLine(cx + dp(18f) - wingLen, wingY, cx + dp(18f) + wingLen, wingY, wingPaint)

        // Degree text
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
// Square area. Dark background with subtle dot grid + concentric G rings.
// Amber dot with blur glow moves with linear acceleration (1G = edge).
// Amber trail fades over 6 seconds.
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
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.FILL
    }
    private val gRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E1E1E")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E65100")
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
    }

    init {
        // BlurMaskFilter requires software rendering
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        val r = dp(8f)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)

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

        // Axis cross-hair
        canvas.drawLine(0f, cy, w, cy, crosshairPaint)
        canvas.drawLine(cx, 0f, cx, h, crosshairPaint)

        // Concentric G rings at 0.5G and 1.0G radius
        canvas.drawCircle(cx, cy, cx * 0.5f, gRingPaint)
        canvas.drawCircle(cx, cy, cx, gRingPaint)

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

// ---------------------------------------------------------------------------
// GpsInfoView
//
// Split instrument panel: three equal columns showing GPS satellite count,
// position accuracy, and location update rate.  Fixed 50dp height to match
// LeanAngleView.
// ---------------------------------------------------------------------------
class GpsInfoView(context: Context) : View(context) {

    private var satellites:   Int   = 0
    private var accuracyM:    Float = 0f
    private var updateRateHz: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        textSize = sp(8f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(18f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    fun update(satellites: Int, accuracyM: Float, updateRateHz: Float) {
        this.satellites   = satellites
        this.accuracyM    = accuracyM
        this.updateRateHz = updateRateHz
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(50f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val colW = w / 3f

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, dp(4f), dp(4f), bgPaint)

        // Vertical dividers
        canvas.drawLine(colW,       dp(6f), colW,       h - dp(6f), dividerPaint)
        canvas.drawLine(colW * 2f,  dp(6f), colW * 2f,  h - dp(6f), dividerPaint)

        // Column data: label, value string, unit string
        data class ColData(val label: String, val value: String, val unit: String)
        val cols = listOf(
            ColData("SAT",  satellites.toString(),         ""),
            ColData("ACC",  "%.1fm".format(accuracyM),     ""),
            ColData("RATE", "%dHz".format(updateRateHz.toInt()), "")
        )

        for ((i, col) in cols.withIndex()) {
            val cx = colW * i + colW / 2f

            // Label — near top
            val labelY = dp(10f) - (labelPaint.ascent())
            canvas.drawText(col.label, cx, labelY, labelPaint)

            // Value — vertically centred
            val valueVCenter = (valuePaint.descent() + valuePaint.ascent()) / 2f
            val valueY = h / 2f - valueVCenter + dp(2f)
            canvas.drawText(col.value, cx, valueY, valuePaint)
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}
