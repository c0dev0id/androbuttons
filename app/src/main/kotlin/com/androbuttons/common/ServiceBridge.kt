package com.androbuttons.common

import android.appwidget.AppWidgetHost
import android.content.Context
import android.view.View

/**
 * The narrow interface through which panes reach back into OverlayService.
 *
 * OverlayService implements this interface directly, so no extra wrapper is needed.
 * Keeping it small ensures panes stay decoupled from the host service.
 */
interface ServiceBridge {

    /** The service Context — use for system services, inflating views, etc. */
    val context: Context

    /** The app's shared [AppWidgetHost] — used to create and manage hosted Android widgets. */
    val appWidgetHost: AppWidgetHost

    /** Animate the overlay off-screen and remove it from the window manager. */
    fun hideOverlay()

    /**
     * A touch listener that detects horizontal swipe gestures and navigates
     * between panes. Attach it to any view inside the pane that would otherwise
     * consume touch events (ScrollView, etc.) so swipes still work there.
     */
    fun makePaneSwipeListener(): View.OnTouchListener

    // ---- SharedPreferences access -------------------------------------------

    fun getIntPref(key: String, default: Int): Int
    fun putIntPref(key: String, value: Int)
    fun getStringPref(key: String, default: String?): String?
    fun putStringPref(key: String, value: String)
    fun getFloatPref(key: String, default: Float): Float
    fun putFloatPref(key: String, value: Float)
}
