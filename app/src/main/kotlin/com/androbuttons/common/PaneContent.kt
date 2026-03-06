package com.androbuttons.common

import android.view.View

/**
 * Contract that every pane must implement.
 *
 * To add a new pane:
 *  1. Create a class in panes/<name>/ that implements this interface.
 *  2. Add one line to the `panes` list in OverlayService.buildFlipperView().
 *
 * Everything else (title bar, dot indicator, left/right navigation, swipe,
 * lifecycle calls) is handled automatically by OverlayService.
 */
interface PaneContent {

    /** Text shown in the title bar when this pane is active. */
    val title: String

    /**
     * Build and return the pane root view.
     * Called once when the overlay is opened. Heavy initialisation (media
     * connection, sensor start) should happen here or in [onResumed].
     */
    fun buildView(): View

    // ---- Key navigation (semantic actions, not raw key codes) ---------------
    // Return true if the event was consumed, false to let OverlayService handle it.

    fun onUp(): Boolean = false
    fun onDown(): Boolean = false
    fun onEnter(): Boolean = false

    /**
     * Return false to signal that the overlay should be closed.
     * (OverlayService calls hideOverlay() when false is returned.)
     */
    fun onCancel(): Boolean = false

    // ---- Lifecycle ----------------------------------------------------------

    /** Called when this pane becomes the visible pane (including at overlay open). */
    fun onResumed() {}

    /** Called when the user navigates away from this pane. */
    fun onPaused() {}

    /** Called when the overlay is destroyed. Release all resources here. */
    fun onDestroy() {}
}
