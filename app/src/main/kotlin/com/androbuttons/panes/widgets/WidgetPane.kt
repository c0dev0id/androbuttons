package com.androbuttons.panes.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.ComponentName
import android.view.ViewGroup
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.dpWith

class WidgetPane(private val bridge: ServiceBridge, private val paneId: String) : PaneContent {

    override val title: String
        get() = bridge.getStringPref("${paneId}_title", "Widgets") ?: "Widgets"

    private val ctx get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private var paneRoot: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var inConfigureView = false

    // SharedPreferences listener: fires on the main thread as soon as WidgetPickerActivity
    // writes the new widget ID, so the pane refreshes without waiting for navigation.
    private val prefs: SharedPreferences =
        bridge.context.getSharedPreferences("androbuttons_prefs", Context.MODE_PRIVATE)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "${paneId}_appwidget_ids") {
            if (inConfigureView) showConfigureView() else showWidgetView()
        }
    }

    // Tracks which widget slot (by index into the stored ID list) has focus.
    // -1 = no widget focused yet (first focus will go to index 0 on first onDown/onUp)
    private var focusIndex = -1

    // Parallel list: one wrapper view per hosted widget, in display order.
    private val slotWrappers = mutableListOf<LinearLayout>()

    // Cache: keeps AppWidgetHostViews alive across showWidgetView() calls so that
    // live RemoteViews are not lost when the pane is rebuilt (e.g. on onResumed()).
    private val widgetViewCache = mutableMapOf<Int, AppWidgetHostView>()

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
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        showWidgetView()
        return pane
    }

    override fun onResumed() {
        if (inConfigureView) showConfigureView() else showWidgetView()
    }

    override fun onPaused() { /* AppWidgetHost lifecycle is handled by OverlayService */ }
    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        widgetViewCache.clear()
    }

    // ---- Key navigation -----------------------------------------------------

    override fun onUp(): Boolean {
        if (inConfigureView) return false
        val ids = loadWidgetIds()
        if (ids.isEmpty()) return false
        focusIndex = if (focusIndex <= 0) ids.size - 1 else focusIndex - 1
        updateFocusHighlight(ids.size)
        scrollToFocused()
        return true
    }

    override fun onDown(): Boolean {
        if (inConfigureView) return false
        val ids = loadWidgetIds()
        if (ids.isEmpty()) return false
        focusIndex = if (focusIndex < 0 || focusIndex >= ids.size - 1) 0 else focusIndex + 1
        updateFocusHighlight(ids.size)
        scrollToFocused()
        return true
    }

    override fun onEnter(): Boolean {
        if (inConfigureView) return false
        val wrappers = slotWrappers
        if (focusIndex < 0 || focusIndex >= wrappers.size) return false
        val slot = wrappers[focusIndex]
        // Find the AppWidgetHostView child of the slot wrapper
        val widgetView = (0 until slot.childCount).map { slot.getChildAt(it) }
            .firstOrNull { it is AppWidgetHostView } ?: return false
        // Dispatch synthetic tap at the centre of the widget view
        val cx = widgetView.width / 2f
        val cy = widgetView.height / 2f
        val now = SystemClock.uptimeMillis()
        widgetView.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0))
        widgetView.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, cx, cy, 0))
        return true
    }

    override fun onCancel(): Boolean {
        if (inConfigureView) return false
        if (focusIndex < 0 || focusIndex >= slotWrappers.size) return false
        removeWidgetAtIndex(focusIndex)
        return true
    }

    private fun removeWidgetAtIndex(index: Int) {
        val ids = loadWidgetIds().toMutableList()
        if (index < 0 || index >= ids.size) return
        val removedId = ids.removeAt(index)
        bridge.appWidgetHost.deleteAppWidgetId(removedId)
        widgetViewCache.remove(removedId)
        bridge.putIntPref("${paneId}_${removedId}_height", 0)
        focusIndex = if (ids.isEmpty()) -1 else index.coerceAtMost(ids.size - 1)
        saveWidgetIds(ids) // prefListener fires → rebuilds view
    }

    // ---- Widget view (normal mode) ------------------------------------------

    private fun showWidgetView() {
        inConfigureView = false
        val pane = paneRoot ?: return
        pane.removeAllViews()
        slotWrappers.clear()

        val ids = loadWidgetIds()
        val manager = AppWidgetManager.getInstance(ctx)

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        }

        if (ids.isEmpty()) {
            inner.addView(TextView(ctx).apply {
                text = "No widgets added.\nUse Configure to add widgets."
                textSize = 13f
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(16.dp(), 40.dp(), 16.dp(), 40.dp())
            })
        } else {
            // Clamp focusIndex in case widgets were removed externally
            if (focusIndex >= ids.size) focusIndex = ids.size - 1

            ids.forEachIndexed { i, appWidgetId ->
                val info = manager.getAppWidgetInfo(appWidgetId)
                val wrapper = buildWidgetSlot(i, appWidgetId, info, isFocused = i == focusIndex)
                slotWrappers.add(wrapper)
                inner.addView(wrapper)
            }
        }

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
            addView(inner)
        }

        this.scrollView = scrollView
        pane.addView(scrollView)
        pane.addView(configureToggleButton())
    }

    private fun buildWidgetSlot(
        index: Int,
        appWidgetId: Int,
        info: AppWidgetProviderInfo?,
        isFocused: Boolean
    ): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            background = focusBorder(isFocused)
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
        }

        if (info != null) {
            val hostView = widgetViewCache.getOrPut(appWidgetId) {
                val newView = bridge.appWidgetHost.createView(ctx, appWidgetId, info)
                // Force the provider to resend its current RemoteViews to the newly
                // registered view — the Android framework does not replay cached state.
                val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = ComponentName(info.provider.packageName, info.provider.className)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                }
                try { ctx.sendBroadcast(updateIntent) } catch (_: Exception) { /* best-effort */ }
                newView
            }
            // Detach from previous parent wrapper before adding to the new one
            (hostView.parent as? ViewGroup)?.removeView(hostView)
            val storedDp = bridge.getIntPref("${paneId}_${appWidgetId}_height", 0)
            val heightPx = if (storedDp > 0) storedDp.dp() else resolveWidgetHeight(info)
            hostView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
            wrapper.addView(hostView)
        } else {
            // Widget info unavailable (app uninstalled, etc.)
            wrapper.addView(TextView(ctx).apply {
                text = "Widget unavailable (app may be uninstalled)"
                textSize = 12f
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(8.dp(), 20.dp(), 8.dp(), 20.dp())
            })
        }
        return wrapper
    }

    // ---- Configure view (placeholder mode) ----------------------------------

    private fun showConfigureView() {
        inConfigureView = true
        val pane = paneRoot ?: return
        pane.removeAllViews()
        slotWrappers.clear()

        val ids = loadWidgetIds()
        val manager = AppWidgetManager.getInstance(ctx)

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        }

        // Placeholder card for each existing widget
        ids.forEachIndexed { i, appWidgetId ->
            val info = manager.getAppWidgetInfo(appWidgetId)
            inner.addView(buildPlaceholderCard(i, appWidgetId, info))
        }

        // "Add" placeholder card
        inner.addView(buildAddCard())

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
            addView(inner)
        }
        this.scrollView = scrollView

        pane.addView(scrollView)
        pane.addView(configureToggleButton())
    }

    private fun buildPlaceholderCard(
        index: Int,
        appWidgetId: Int,
        info: AppWidgetProviderInfo?
    ): LinearLayout {
        val label = if (info != null) {
            info.loadLabel(ctx.packageManager) ?: "Widget"
        } else {
            "Widget unavailable"
        }

        val defaultDp = if (info != null) {
            (resolveWidgetHeight(info) / ctx.resources.displayMetrics.density).toInt()
        } else 120
        val currentDp = bridge.getIntPref("${paneId}_${appWidgetId}_height", 0)
            .takeIf { it > 0 } ?: defaultDp

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(Theme.inactiveBg)
            }
            setPadding(14.dp(), 16.dp(), 10.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }

            // Widget name
            addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // − button
            addView(TextView(ctx).apply {
                text = "−"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.primary)
                setPadding(10.dp(), 6.dp(), 6.dp(), 6.dp())
                isClickable = true
                setOnClickListener {
                    val newDp = (currentDp - 72).coerceAtLeast(100)
                    bridge.putIntPref("${paneId}_${appWidgetId}_height", newDp)
                    showConfigureView()
                }
            })

            // Current height label
            addView(TextView(ctx).apply {
                text = "${currentDp}dp"
                textSize = 11f
                setTextColor(Theme.textSecondary)
                setPadding(4.dp(), 6.dp(), 4.dp(), 6.dp())
                gravity = Gravity.CENTER
            })

            // + button
            addView(TextView(ctx).apply {
                text = "+"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.primary)
                setPadding(6.dp(), 6.dp(), 10.dp(), 6.dp())
                isClickable = true
                setOnClickListener {
                    bridge.putIntPref("${paneId}_${appWidgetId}_height", currentDp + 72)
                    showConfigureView()
                }
            })

            // Remove button
            addView(TextView(ctx).apply {
                text = "Remove"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.primary)
                setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
                isClickable = true
                setOnClickListener { removeWidgetAtIndex(index) }
            })
        }
    }

    private fun buildAddCard(): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(Theme.inactiveBg)
            }
            setPadding(14.dp(), 16.dp(), 14.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }

            addView(TextView(ctx).apply {
                text = "Add"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = actionButtonBg(Theme.primary, ctx)
                setPadding(24.dp(), 10.dp(), 24.dp(), 10.dp())
                isClickable = true
                setOnClickListener {
                    val intent = Intent(ctx, WidgetPickerActivity::class.java).apply {
                        putExtra(WidgetPickerActivity.EXTRA_PANE_ID, paneId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                }
            })
        }
    }

    // ---- UI helpers ---------------------------------------------------------

    private fun configureToggleButton() = TextView(ctx).apply {
        text = if (inConfigureView) "Done" else "Configure"
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (inConfigureView) Color.WHITE else Theme.textSecondary)
        background = actionButtonBg(
            if (inConfigureView) Theme.primary else Theme.inactiveBg, ctx
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8.dp(); leftMargin = 10.dp(); rightMargin = 10.dp(); bottomMargin = 10.dp() }
        setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
        isClickable = true
        setOnClickListener {
            if (inConfigureView) showWidgetView() else showConfigureView()
        }
    }

    /** Converts widget's declared size to pixels, with a sensible minimum. */
    private fun resolveWidgetHeight(info: AppWidgetProviderInfo): Int {
        val minHeightDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) {
            info.targetCellHeight * 72  // ~72 dp per standard home-screen cell (API 31+)
        } else if (info.minResizeHeight > 0) {
            info.minResizeHeight
        } else {
            info.minHeight
        }
        return if (minHeightDp > 0) minHeightDp.dp().coerceAtLeast(100.dp()) else 120.dp()
    }

    private fun focusBorder(focused: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 6.dp().toFloat()
        if (focused) {
            setColor(Color.TRANSPARENT)
            setStroke(2.dp(), Theme.primary)
        } else {
            setColor(Color.TRANSPARENT)
        }
    }

    private fun updateFocusHighlight(count: Int) {
        for (i in 0 until count) {
            slotWrappers.getOrNull(i)?.background = focusBorder(i == focusIndex)
        }
    }

    private fun scrollToFocused() {
        val wrapper = slotWrappers.getOrNull(focusIndex) ?: return
        val sv = scrollView ?: return
        sv.post { sv.smoothScrollTo(0, wrapper.top) }
    }

    // ---- Prefs helpers ------------------------------------------------------

    private fun loadWidgetIds(): List<Int> {
        val raw = bridge.getStringPref("${paneId}_appwidget_ids", null)
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()
    }

    private fun saveWidgetIds(ids: List<Int>) {
        bridge.putStringPref("${paneId}_appwidget_ids", ids.joinToString(","))
    }
}
