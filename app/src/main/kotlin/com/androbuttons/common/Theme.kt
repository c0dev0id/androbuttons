package com.androbuttons.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue

/**
 * App-wide colour palette.
 * All panes import this object to keep colours consistent.
 */
object Theme {
    val primary       = Color.parseColor("#F57C00")   // orange — focus / active state
    val surface       = Color.parseColor("#FF2B2B2B") // opaque dark grey (main bg)
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
