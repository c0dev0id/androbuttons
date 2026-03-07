package com.androbuttons.panes.pointers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

// ---------------------------------------------------------------------------
// PointerArrowView
//
// Square instrument that shows a directional arrow pointing toward a POI.
// "Up" in the view equals the riding direction (forward):
//   0°  → arrow points up    = POI is straight ahead
//  90°  → arrow points right = POI is to the right
// -90°  → arrow points left  = POI is to the left
// The riding direction comes from GPS bearing when moving (>5 km/h),
// otherwise from the compass (rotation vector azimuth).
// ---------------------------------------------------------------------------
class PointerArrowView(context: Context) : View(context) {

    private var relativeBearingDeg: Float = 0f
    private var labelText: String = "---"
    private var distanceText: String = "---"

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        style = Paint.Style.FILL
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(9f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F57C00")
        textSize = sp(8f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    fun setRelativeBearing(degrees: Float) {
        relativeBearingDeg = degrees
        invalidate()
    }

    fun setLabel(name: String) {
        labelText = name
        invalidate()
    }

    fun setDistance(km: Float) {
        distanceText = if (km < 1f) "${(km * 1000).toInt()} m" else "${"%.1f".format(km)} km"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), bgPaint)

        // Reserve a strip at the bottom for name + distance labels
        val labelAreaH = h * 0.26f
        val arrowAreaH = h - labelAreaH
        val cx = w / 2f
        val cy = arrowAreaH / 2f

        val arrowLen = minOf(cx, cy) * 0.70f  // centre → tip
        val headLen  = arrowLen * 0.28f        // arrowhead height
        val headW    = arrowLen * 0.20f        // arrowhead half-width at base
        val tailLen  = arrowLen * 0.35f        // shaft below centre

        // Rotate the arrow around the arrow-area centre
        canvas.save()
        canvas.rotate(relativeBearingDeg, cx, cy)

        // Shaft from just below arrowhead to the tail
        canvas.drawLine(cx, cy - arrowLen + headLen, cx, cy + tailLen, arrowPaint)

        // Arrowhead triangle (tip points up = forward by default)
        val headPath = Path().apply {
            moveTo(cx, cy - arrowLen)
            lineTo(cx - headW, cy - arrowLen + headLen)
            lineTo(cx + headW, cy - arrowLen + headLen)
            close()
        }
        canvas.drawPath(headPath, arrowPaint)

        canvas.restore()

        // Amber pivot dot at the rotation centre
        canvas.drawCircle(cx, cy, dp(3.5f), pivotPaint)

        // Labels (not rotated)
        val labelTop     = arrowAreaH + dp(4f)
        val distBaseline = labelTop - distPaint.ascent()
        val nameBaseline = distBaseline + distPaint.descent() + dp(2f) - namePaint.ascent()

        canvas.drawText(distanceText, cx, distBaseline, distPaint)
        canvas.drawText(labelText,    cx, nameBaseline,  namePaint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )
}
