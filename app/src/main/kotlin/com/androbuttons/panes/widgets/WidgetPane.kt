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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        if (key == "${paneId}_appwidget_ids" && !inConfigureView) showWidgetView()
    }

    // Tracks which widget slot (by index into the stored ID list) has focus.
    // -1 = no widget focused yet (first focus will go to index 0 on first onDown/onUp)
    private var focusIndex = -1

    // Parallel list: one wrapper view per hosted widget, in display order.
    private val slotWrappers = mutableListOf<LinearLayout>()

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
        // Rebuild the widget view in case the user just added a widget via
        // WidgetPickerActivity while this pane was in the background.
        if (!inConfigureView) showWidgetView()
    }

    override fun onPaused() { /* AppWidgetHost lifecycle is handled by OverlayService */ }
    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
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
        // Remove the focused widget
        val ids = loadWidgetIds().toMutableList()
        if (focusIndex >= ids.size) return false
        val removedId = ids.removeAt(focusIndex)
        bridge.appWidgetHost.deleteAppWidgetId(removedId)
        saveWidgetIds(ids)
        focusIndex = if (ids.isEmpty()) -1 else focusIndex.coerceAtMost(ids.size - 1)
        showWidgetView()
        return true
    }

    // ---- Widget view --------------------------------------------------------

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
                text = "No widgets added.\nTap ➕ to add an Android widget."
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
                val wrapper = buildWidgetSlot(appWidgetId, info, isFocused = i == focusIndex)
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
        pane.addView(addWidgetButton())
        pane.addView(configureButton())
    }

    private fun buildWidgetSlot(
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
            val hostView = bridge.appWidgetHost.createView(ctx, appWidgetId, info)
            val heightPx = resolveWidgetHeight(info)
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

    // ---- Configure view (title only) ----------------------------------------

    private fun showConfigureView() {
        inConfigureView = true
        val pane = paneRoot ?: return
        pane.removeAllViews()
        slotWrappers.clear()

        val titleEdit = EditText(ctx).apply {
            setText(bridge.getStringPref("${paneId}_title", "Widgets"))
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Theme.textSecondary)
            hint = "Pane title"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(Theme.inactiveBg)
            }
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dp(); leftMargin = 10.dp()
                rightMargin = 10.dp(); bottomMargin = 6.dp()
            }
            setTypeface(null, Typeface.BOLD)
        }

        pane.addView(titleEdit)
        pane.addView(saveButton {
            val newTitle = titleEdit.text.toString().trim().ifBlank { "Widgets" }
            bridge.putStringPref("${paneId}_title", newTitle)
            showWidgetView()
        })
    }

    // ---- UI helpers ---------------------------------------------------------

    private fun addWidgetButton() = TextView(ctx).apply {
        text = "➕  Add Widget"
        textSize = 15f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = actionButtonBg(Theme.primary, ctx)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 10.dp(); rightMargin = 10.dp(); topMargin = 8.dp(); bottomMargin = 4.dp() }
        setPadding(12.dp(), 16.dp(), 12.dp(), 16.dp())
        isClickable = true
        setOnClickListener {
            val intent = Intent(ctx, WidgetPickerActivity::class.java).apply {
                putExtra(WidgetPickerActivity.EXTRA_PANE_ID, paneId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
    }

    private fun configureButton() = TextView(ctx).apply {
        text = "Rename Pane"
        textSize = 14f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(Theme.textSecondary)
        background = actionButtonBg(Theme.inactiveBg, ctx)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 10.dp(); rightMargin = 10.dp(); topMargin = 0.dp(); bottomMargin = 10.dp() }
        setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
        isClickable = true
        setOnClickListener { showConfigureView() }
    }

    private fun saveButton(onSave: () -> Unit) = TextView(ctx).apply {
        text = "Save"
        textSize = 16f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = actionButtonBg(Theme.primary, ctx)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8.dp(); leftMargin = 10.dp(); rightMargin = 10.dp(); bottomMargin = 10.dp() }
        setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
        isClickable = true
        setOnClickListener { onSave() }
    }

    // ---- Prefs helpers ------------------------------------------------------

    private fun loadWidgetIds(): List<Int> {
        val raw = bridge.getStringPref("${paneId}_appwidget_ids", null)
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    private fun saveWidgetIds(ids: List<Int>) {
        bridge.putStringPref("${paneId}_appwidget_ids", ids.joinToString(","))
    }
}
