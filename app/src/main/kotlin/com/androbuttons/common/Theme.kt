package com.androbuttons.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue

/**
 * App-wide colour palette.
 * All panes import this object to keep colours consistent.
 */
object Theme {
    val primary       = Color.parseColor("#F57C00")   // orange — focus / active state
    val surface       = Color.parseColor("#FF222222") // dark leather grey (main bg)
    val header        = Color.parseColor("#FF3D3D3D") // slightly lighter header bar
    val playerArea    = Color.parseColor("#FF1E1E1E") // darker player-card bg
    val textSecondary = Color.parseColor("#B0B0B0")
    val textTertiary  = Color.parseColor("#808080")
    val inactiveBg    = Color.parseColor("#444444")
    val seekTrack     = Color.parseColor("#555555")
    val playingRow    = Color.parseColor("#28F57C00") // ~16 % orange tint for current track
}

// ---------------------------------------------------------------------------
// Dimension helpers
// ---------------------------------------------------------------------------

/** Convert dp to pixels using the given context's display metrics. */
fun Int.dpWith(ctx: Context): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        ctx.resources.displayMetrics
    ).toInt()

// ---------------------------------------------------------------------------
// LeatherDrawable
// Renders: base colour fill + subtle grain texture + left-edge dark gradient.
// ---------------------------------------------------------------------------

class LeatherDrawable(
    private val baseColor: Int,
    private val radiiPx: FloatArray,
    private val edgeGradientWidthPx: Int = 0
) : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()

    private var grainBitmap: Bitmap? = null
    private var lastW = 0
    private var lastH = 0

    /** Generates a static noise bitmap that looks like subtle leather grain. */
    private fun buildGrain(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val rng = java.util.Random(42L)
        val total = w * h / 7
        for (i in 0 until total) {
            val x = rng.nextInt(w).toFloat()
            val y = rng.nextInt(h).toFloat()
            val v = rng.nextInt(36) - 18    // ±18 brightness offset
            p.color = if (v > 0)
                Color.argb(v * 3, 255, 255, 255)   // light speckle
            else
                Color.argb(-v * 3, 0, 0, 0)         // dark speckle
            c.drawPoint(x, y, p)
        }
        return bmp
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width(); val h = bounds.height()
        if (w != lastW || h != lastH) {
            lastW = w; lastH = h
            grainBitmap?.recycle()
            grainBitmap = if (w > 0 && h > 0) buildGrain(w, h) else null
        }
        // Rebuild clip path for rounded shape
        clipPath.reset()
        clipPath.addRoundRect(RectF(bounds), radiiPx, Path.Direction.CW)

        // Rebuild left-edge gradient shader
        if (edgeGradientWidthPx > 0) {
            edgePaint.shader = LinearGradient(
                bounds.left.toFloat(), 0f,
                bounds.left + edgeGradientWidthPx.toFloat(), 0f,
                intArrayOf(Color.parseColor("#D9000000"), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val boundsF = RectF(bounds)
        canvas.save()
        canvas.clipPath(clipPath)

        // 1. Base leather colour
        basePaint.color = baseColor
        canvas.drawRect(boundsF, basePaint)

        // 2. Grain overlay
        grainBitmap?.let {
            canvas.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), bitmapPaint)
        }

        // 3. Left-edge darkening (leather wrapping around the edge)
        if (edgeGradientWidthPx > 0) {
            canvas.drawRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.left + edgeGradientWidthPx.toFloat(), bounds.bottom.toFloat(),
                edgePaint
            )
        }

        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { basePaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { basePaint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------------------
// Shared drawable factories
// Used by multiple panes so they live here rather than being duplicated.
// ---------------------------------------------------------------------------

/** Solid rounded-rectangle background. */
fun roundedBg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.dpWith(ctx).toFloat()
        setColor(color)
    }

/**
 * Layered rounded background with a near-black bezel (3 dp) around an inner fill.
 * Gives elements a sunken/recessed appearance against the leather panel.
 */
fun bezeledBg(innerColor: Int, cornerDp: Int, ctx: Context): LayerDrawable {
    val outerR = cornerDp.dpWith(ctx).toFloat()
    val insetPx = 3.dpWith(ctx)
    val innerR = maxOf(0f, outerR - insetPx)

    val bezel = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = outerR
        setColor(Color.parseColor("#080808"))
    }
    val inner = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = innerR
        setColor(innerColor)
    }
    return LayerDrawable(arrayOf(bezel, inner)).also { ld ->
        ld.setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
    }
}

/**
 * Very dark rounded background used as the wrapper container for instrument views,
 * creating a near-black bezel/rim that makes the element look sunken into leather.
 */
fun sunkenInstrumentBg(cornerDp: Int, ctx: Context): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerDp.dpWith(ctx).toFloat()
        setColor(Color.parseColor("#080808"))
    }

/**
 * Button background: solid colour + ripple. Used for the Configure / Save /
 * Calibrate buttons that do not need a focus stroke.
 */
fun actionButtonBg(color: Int, ctx: Context): RippleDrawable {
    val r = 8.dpWith(ctx).toFloat()
    val bg   = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(color) }
    val mask = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), bg, mask)
}

/**
 * Interactive button background that shows an optional orange focus stroke.
 * Used for the play/pause button and app launcher buttons.
 */
fun buttonBg(isFocused: Boolean, ctx: Context): RippleDrawable {
    val r = 8.dpWith(ctx).toFloat()
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = r
        setColor(Theme.inactiveBg)
        if (isFocused) setStroke(2.dpWith(ctx), Theme.primary)
    }
    val mask = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), bg, mask)
}
