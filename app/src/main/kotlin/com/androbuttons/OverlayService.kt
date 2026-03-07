package com.androbuttons

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetHost
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.core.app.NotificationCompat
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith
import com.androbuttons.panes.apps.AppsPane
import com.androbuttons.panes.markers.MarkersPane
import com.androbuttons.panes.music.MusicPane
import com.androbuttons.panes.pointers.PointersPane
import com.androbuttons.panes.sensors.SensorsPane
import com.androbuttons.panes.system.SystemPane
import com.androbuttons.panes.widgets.AppWidgetHostManager
import com.androbuttons.panes.widgets.WidgetPane

class OverlayService : Service(), ServiceBridge {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "androbuttons_overlay"
        private const val NOTIFICATION_ID         = 1
        private const val PREFS_NAME              = "androbuttons_prefs"
        private const val KEY_UP     = "key_up"
        private const val KEY_DOWN   = "key_down"
        private const val KEY_LEFT   = "key_left"
        private const val KEY_RIGHT  = "key_right"
        private const val KEY_ENTER  = "key_enter"
        private const val KEY_CANCEL = "key_cancel"
        private const val DEFAULT_KEY_UP     = KeyEvent.KEYCODE_DPAD_UP
        private const val DEFAULT_KEY_DOWN   = KeyEvent.KEYCODE_DPAD_DOWN
        private const val DEFAULT_KEY_LEFT   = KeyEvent.KEYCODE_DPAD_LEFT
        private const val DEFAULT_KEY_RIGHT  = KeyEvent.KEYCODE_DPAD_RIGHT
        private const val DEFAULT_KEY_ENTER  = KeyEvent.KEYCODE_ENTER
        private const val DEFAULT_KEY_CANCEL = KeyEvent.KEYCODE_ESCAPE
        private const val SECONDARY_KEY_ENTER  = KeyEvent.KEYCODE_BUTTON_Y
        private const val SECONDARY_KEY_CANCEL = KeyEvent.KEYCODE_BUTTON_A

        private const val KEY_PANE_ORDER    = "pane_order"
        private const val KEY_WIDGET_NEXTID = "widget_next_id"
        private const val DEFAULT_PANE_ORDER = "music,apps,sensors,markers,pointers,system"

        private val FIXED_PANE_LABELS = mapOf(
            "music"    to "Music",
            "apps"     to "Apps",
            "sensors"  to "Sensors",
            "markers"  to "Markers",
            "pointers" to "Pointers",
            "system"   to "System"
        )

        var isRunning = false
    }

    // ---- Android service boilerplate ----------------------------------------

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // ---- Pane host ----------------------------------------------------------

    private lateinit var panes: List<PaneContent>
    private var currentPane = 0

    private lateinit var viewFlipper: ViewFlipper
    private var titleArrowLeft:  ImageView?     = null
    private var titleText:       TextView?      = null
    private var titleArrowRight: ImageView?     = null
    private var titleLeftZone:   LinearLayout?  = null
    private var titleRightZone:  LinearLayout?  = null
    private val paneDots = mutableListOf<View>()

    // ---- Pane manager -------------------------------------------------------

    private var rootContainer: LinearLayout? = null
    private var inPaneManager = false
    private var paneManagerView: View? = null
    private val managerOrder = mutableListOf<String>()
    private var managerSortingMode = false
    private var managerSortDragIndex = -1
    private val managerRowViews = mutableListOf<LinearLayout>()
    private var managerRowScroll: ScrollView? = null
    private var managerRowContainer: LinearLayout? = null

    // ---- Dimension helper ---------------------------------------------------

    private fun Int.dp() = dpWith(this@OverlayService)

    // ---- Screen geometry ----------------------------------------------------

    private val statusBarHeight: Int
        get() {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

    private val navBarHeight: Int
        get() {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

    private val visibleStatusBarHeight: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics
                .windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        } else statusBarHeight

    private val visibleNavBarHeight: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics
                .windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        } else navBarHeight

    private val overlayWidth: Int
        get() {
            val fraction = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 0.45f else (1f / 3f)
            return (resources.displayMetrics.widthPixels * fraction).toInt()
        }

    private val overlayHeight: Int
        get() {
            val screenH = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                windowManager.currentWindowMetrics.bounds.height()
            else resources.displayMetrics.heightPixels
            return screenH - visibleStatusBarHeight - visibleNavBarHeight
        }

    // =========================================================================
    // Service lifecycle
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Instantiate panes from saved order; views are built lazily in showOverlay()
        panes = buildPanes()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        AppWidgetHostManager.getHost(this).startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView != null) hideOverlay() else showOverlay()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        panes.forEach { it.onDestroy() }
        removeOverlay()
        AppWidgetHostManager.getHost(this).stopListening()
    }

    // =========================================================================
    // ServiceBridge implementation
    // =========================================================================

    override val context: Context get() = this
    override val appWidgetHost: AppWidgetHost get() = AppWidgetHostManager.getHost(this)

    override fun hideOverlay() {
        val view = overlayView ?: return
        view.animate()
            .translationX(view.width.toFloat())
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { view.visibility = View.INVISIBLE; removeOverlay() }
            .start()
    }

    override fun makePaneSwipeListener(): View.OnTouchListener {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD          = 40
            private val SWIPE_VELOCITY_THRESHOLD = 80

            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val diffX = e2.x - (e1?.x ?: 0f)
                return if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX < 0 && currentPane < panes.size - 1) navigateToPane(currentPane + 1)
                    else if (diffX > 0 && currentPane > 0)         navigateToPane(currentPane - 1)
                    true
                } else false
            }
        })
        return View.OnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    override fun getIntPref(key: String, default: Int)          = prefs.getInt(key, default)
    override fun putIntPref(key: String, value: Int)             { prefs.edit().putInt(key, value).apply() }
    override fun getStringPref(key: String, default: String?)    = prefs.getString(key, default)
    override fun putStringPref(key: String, value: String)       { prefs.edit().putString(key, value).apply() }
    override fun getFloatPref(key: String, default: Float)       = prefs.getFloat(key, default)
    override fun putFloatPref(key: String, value: Float)         { prefs.edit().putFloat(key, value).apply() }

    // =========================================================================
    // Overlay lifecycle
    // =========================================================================

    private fun showOverlay() {
        removeOverlay()
        val view = buildRootView()
        val params = WindowManager.LayoutParams(
            overlayWidth, overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = 0
            y = visibleStatusBarHeight
        }
        windowParams = params
        view.translationX = overlayWidth.toFloat()
        windowManager.addView(view, params)
        overlayView = view
        view.requestFocus()
        view.animate()
            .translationX(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    // =========================================================================
    // UI building
    // =========================================================================

    private fun buildRootView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // No padding — header bar fills edge-to-edge; content padding applied inside sections
            // Semi-transparent (~90% opacity) so the underlying app bleeds through
            // Left side rounded, right side flush against the screen edge
            background = createLeftRoundedBackground(
                color = Theme.surface and 0x00FFFFFF or (230 shl 24),
                radiusDp = 16
            )
            elevation = 8.dp().toFloat()
            clipToOutline = true
        }
        rootContainer = container
        container.addView(buildTitleBar())
        container.addView(buildFlipperView())
        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(keyCode) else false
        }

        return container
    }

    private fun buildTitleBar(): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.header)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                44.dp()
            )
        }

        titleArrowLeft = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_left)
            layoutParams = LinearLayout.LayoutParams(32.dp(), 32.dp())
            setColorFilter(if (currentPane > 0) Theme.primary else Theme.inactiveBg)
        }
        titleText = TextView(this).apply {
            text = panes[currentPane].title; textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER_HORIZONTAL
        }
        titleArrowRight = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_right)
            layoutParams = LinearLayout.LayoutParams(32.dp(), 32.dp())
            setColorFilter(if (currentPane < panes.size - 1) Theme.primary else Theme.inactiveBg)
        }

        titleLeftZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(44.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
            alpha = if (currentPane > 0) 1f else 0.4f
            addView(titleArrowLeft)
            setOnClickListener { if (currentPane > 0) navigateToPane(currentPane - 1) }
        }

        val centerZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply { weight = 1f }
            addView(titleText)
            isLongClickable = true
            setOnLongClickListener { if (!inPaneManager) showPaneManager(); true }
        }

        titleRightZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(44.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
            alpha = if (currentPane < panes.size - 1) 1f else 0.4f
            addView(titleArrowRight)
            setOnClickListener { if (currentPane < panes.size - 1) navigateToPane(currentPane + 1) }
        }

        fun verticalDivider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        bar.addView(titleLeftZone)
        bar.addView(verticalDivider())
        bar.addView(centerZone)
        bar.addView(verticalDivider())
        bar.addView(titleRightZone)
        wrapper.addView(bar)

        // Pane indicator dots
        paneDots.clear()
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp() }
            isLongClickable = true
            setOnLongClickListener { if (!inPaneManager) showPaneManager(); true }
        }
        for (i in panes.indices) {
            val dot = View(this).apply {
                val size = if (i == currentPane) 6.dp() else 5.dp()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 3.dp(); marginEnd = 3.dp() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == currentPane) Theme.primary else Theme.inactiveBg)
                }
            }
            paneDots.add(dot); dotsRow.addView(dot)
        }
        centerZone.addView(dotsRow)

        wrapper.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp())
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
        })
        return wrapper
    }

    private fun refreshTitleBar() {
        titleText?.text = panes[currentPane].title
        val leftActive  = currentPane > 0
        val rightActive = currentPane < panes.size - 1
        titleArrowLeft?.setColorFilter(if (leftActive)  Theme.primary else Theme.inactiveBg)
        titleArrowRight?.setColorFilter(if (rightActive) Theme.primary else Theme.inactiveBg)
        titleLeftZone?.alpha  = if (leftActive)  1f else 0.4f
        titleRightZone?.alpha = if (rightActive) 1f else 0.4f
        paneDots.forEachIndexed { i, dot ->
            val active = i == currentPane
            val size = if (active) 6.dp() else 5.dp()
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 3.dp(); marginEnd = 3.dp() }
            (dot.background as? GradientDrawable)?.setColor(if (active) Theme.primary else Theme.inactiveBg)
        }
    }

    private fun buildFlipperView(): ViewFlipper {
        viewFlipper = ViewFlipper(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        }
        panes.forEach { viewFlipper.addView(it.buildView()) }
        viewFlipper.displayedChild = currentPane
        panes[currentPane].onResumed()
        return viewFlipper
    }

    // =========================================================================
    // Pane configuration
    // =========================================================================

    private fun buildPanes(): List<PaneContent> {
        val raw = prefs.getString(KEY_PANE_ORDER, DEFAULT_PANE_ORDER) ?: DEFAULT_PANE_ORDER
        val result = raw.split(",")
            .filter { it.isNotBlank() && !it.startsWith("~") }
            .mapNotNull { id ->
                when (id) {
                    "music"    -> MusicPane(bridge = this)
                    "apps"     -> AppsPane(bridge = this)
                    "sensors"  -> SensorsPane(bridge = this)
                    "markers"  -> MarkersPane(bridge = this)
                    "pointers" -> PointersPane(bridge = this)
                    "system"   -> SystemPane(bridge = this)
                    else       -> if (id.startsWith("widget_")) WidgetPane(this, id) else null
                }
            }
        return result.ifEmpty {
            listOf(MusicPane(this), AppsPane(this), SensorsPane(this), MarkersPane(this), PointersPane(this), SystemPane(this))
        }
    }

    private fun applyNewPaneOrder(newOrder: List<String>) {
        // Remember which pane the user was on before rebuilding
        val oldEnabledIds = (prefs.getString(KEY_PANE_ORDER, DEFAULT_PANE_ORDER) ?: DEFAULT_PANE_ORDER)
            .split(",")
            .filter { it.isNotBlank() && !it.startsWith("~") }
        val currentPaneId = oldEnabledIds.getOrNull(currentPane)

        prefs.edit().putString(KEY_PANE_ORDER, newOrder.joinToString(",")).apply()
        panes.forEach { it.onDestroy() }
        panes = buildPanes()

        // Restore position; fall back to 0 if the pane was disabled or removed
        val newEnabledIds = newOrder.filter { it.isNotBlank() && !it.startsWith("~") }
        currentPane = newEnabledIds.indexOf(currentPaneId).takeIf { it >= 0 } ?: 0

        inPaneManager = false
        showOverlay()
    }

    // =========================================================================
    // Pane manager
    // =========================================================================

    private fun showPaneManager() {
        val raw = prefs.getString(KEY_PANE_ORDER, DEFAULT_PANE_ORDER) ?: DEFAULT_PANE_ORDER
        managerOrder.clear()
        managerOrder.addAll(raw.split(",").filter { it.isNotBlank() })
        inPaneManager = true
        if (panes.isNotEmpty()) panes[currentPane].onPaused()
        viewFlipper.visibility = View.GONE
        val mgr = buildPaneManagerView()
        paneManagerView = mgr
        rootContainer?.addView(mgr, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        titleText?.text = "Manage Panes"
        titleLeftZone?.alpha = 0.4f
        titleRightZone?.alpha = 0.4f
    }

    private fun exitPaneManager() {
        rootContainer?.removeView(paneManagerView)
        paneManagerView = null
        viewFlipper.visibility = View.VISIBLE
        inPaneManager = false
        refreshTitleBar()
        if (panes.isNotEmpty()) panes[currentPane].onResumed()
    }

    private fun buildPaneManagerView(): View {
        managerRowViews.clear()

        val rowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
        }
        managerRowContainer = rowContainer

        managerOrder.forEachIndexed { i, _ ->
            val row = buildManagerRow(i)
            managerRowViews.add(row)
            rowContainer.addView(row)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rowContainer)
        }
        managerRowScroll = scroll

        val addBtn = TextView(this).apply {
            text = "➕  Add Widget Pane"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Theme.textSecondary)
            background = actionButtonBg(Theme.inactiveBg, this@OverlayService)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 10.dp(); rightMargin = 10.dp(); topMargin = 6.dp() }
            setPadding(12.dp(), 16.dp(), 12.dp(), 16.dp())
            isClickable = true
            setOnClickListener { addWidgetPane() }
        }

        val doneBtn = TextView(this).apply {
            text = "Done"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = actionButtonBg(Theme.primary, this@OverlayService)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 10.dp(); rightMargin = 10.dp(); topMargin = 6.dp(); bottomMargin = 10.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = true
            setOnClickListener { applyNewPaneOrder(managerOrder.toList()) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
            addView(addBtn)
            addView(doneBtn)
        }
    }

    private fun buildManagerRow(index: Int): LinearLayout {
        val rawId = managerOrder[index]
        val id = rawId.removePrefix("~")
        val isWidget = id.startsWith("widget_")
        val isEnabled = !rawId.startsWith("~")
        val displayName = FIXED_PANE_LABELS[id]
            ?: prefs.getString("${id}_title", "Widgets") ?: "Widgets"

        val dragHandle = TextView(this).apply {
            text = "≡"
            textSize = 18f
            setTextColor(Theme.textSecondary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(32.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = 10.dp() }
        }

        val nameLabel = TextView(this).apply {
            text = displayName
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isEnabled) Color.WHITE else Theme.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val actionView: TextView = if (isWidget) {
            // ✕ remove button for widget panes
            TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Theme.primary)
                gravity = Gravity.CENTER
                val size = 32.dp()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setOnClickListener { removeWidgetPane(managerRowViews.indexOf(parent as LinearLayout)) }
            }
        } else {
            // Toggle checkbox for fixed panes
            makeManagerCheckbox(isEnabled)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }
            background = buttonBg(false, this@OverlayService)
            addView(dragHandle)
            addView(nameLabel)
            addView(actionView)
        }

        // Drag-to-reorder + single-tap toggle/rename gesture
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isWidget) {
                    startInlineRename(row, id)
                } else {
                    toggleFixedPane(managerRowViews.indexOf(row), actionView, nameLabel)
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                managerSortingMode = true
                managerSortDragIndex = managerRowViews.indexOf(row)
                row.background = buttonBg(true, this@OverlayService)
                managerRowScroll?.requestDisallowInterceptTouchEvent(true)
            }
        })

        row.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN   -> { v.drawableHotspotChanged(event.x, event.y); v.isPressed = true }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            val handled = gestureDetector.onTouchEvent(event)
            val rowIdx = managerRowViews.indexOf(row)
            if (managerSortingMode && managerSortDragIndex == rowIdx) {
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val newIdx = getManagerIndexAtY(event.rawY)
                        if (newIdx != managerSortDragIndex) {
                            swapManagerRows(managerSortDragIndex, newIdx)
                            managerSortDragIndex = newIdx
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        managerSortingMode = false
                        managerSortDragIndex = -1
                        managerRowScroll?.requestDisallowInterceptTouchEvent(false)
                        // Restore non-focused background
                        row.background = buttonBg(false, this@OverlayService)
                    }
                }
                true
            } else handled
        }

        return row
    }

    private fun startInlineRename(row: LinearLayout, paneId: String) {
        // Find the name label (index 1: after drag handle)
        val nameLabel = row.getChildAt(1) as? TextView ?: return
        val currentName = nameLabel.text.toString()

        val editText = EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            layoutParams = nameLabel.layoutParams
            setPadding(0, 0, 0, 0)
        }

        fun finishRename() {
            val newTitle = editText.text.toString().trim().ifBlank { "Widgets" }
            prefs.edit().putString("${paneId}_title", newTitle).apply()
            val restored = TextView(this).apply {
                text = newTitle
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = editText.layoutParams
            }
            val idx = row.indexOfChild(editText)
            if (idx >= 0) {
                row.removeViewAt(idx)
                row.addView(restored, idx)
            }
        }

        editText.setOnEditorActionListener { _, _, _ ->
            finishRename()
            true
        }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) finishRename()
        }

        val idx = row.indexOfChild(nameLabel)
        if (idx >= 0) {
            row.removeViewAt(idx)
            row.addView(editText, idx)
            editText.requestFocus()
        }
    }

    private fun makeManagerCheckbox(checked: Boolean) = TextView(this).apply {
        val size = 26.dp()
        layoutParams = LinearLayout.LayoutParams(size, size)
        gravity = Gravity.CENTER
        text = if (checked) "✓" else ""
        textSize = 13f
        setTextColor(Color.WHITE)
        setTypeface(null, android.graphics.Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4.dp().toFloat()
            if (checked) setColor(Theme.primary)
            else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
        }
    }

    private fun toggleFixedPane(rowIndex: Int, checkbox: TextView, label: TextView) {
        val current = managerOrder[rowIndex]
        val wasDisabled = current.startsWith("~")
        managerOrder[rowIndex] = if (wasDisabled) current.removePrefix("~") else "~$current"
        val nowEnabled = wasDisabled
        // Update checkbox appearance
        checkbox.text = if (nowEnabled) "✓" else ""
        checkbox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4.dp().toFloat()
            if (nowEnabled) setColor(Theme.primary)
            else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
        }
        label.setTextColor(if (nowEnabled) Color.WHITE else Theme.textSecondary)
    }

    private fun removeWidgetPane(rowIndex: Int) {
        if (rowIndex < 0 || rowIndex >= managerOrder.size) return
        val paneId = managerOrder.removeAt(rowIndex)
        val row = managerRowViews.removeAt(rowIndex)
        managerRowContainer?.removeView(row)
        // Release any bound Android widget IDs before clearing prefs
        val widgetIds = prefs.getString("${paneId}_appwidget_ids", null)
        if (!widgetIds.isNullOrBlank()) {
            val host = AppWidgetHostManager.getHost(this)
            widgetIds.split(",").mapNotNull { it.trim().toIntOrNull() }
                .forEach { host.deleteAppWidgetId(it) }
        }
        prefs.edit()
            .remove("${paneId}_title")
            .remove("${paneId}_widgets")
            .remove("${paneId}_appwidget_ids")
            .apply()
    }

    private fun addWidgetPane() {
        val nextId = prefs.getInt(KEY_WIDGET_NEXTID, 0)
        prefs.edit().putInt(KEY_WIDGET_NEXTID, nextId + 1).apply()
        val paneId = "widget_$nextId"
        managerOrder.add(paneId)
        val row = buildManagerRow(managerOrder.size - 1)
        managerRowViews.add(row)
        managerRowContainer?.addView(row)
        // Scroll to bottom so the new pane is visible
        managerRowScroll?.post { managerRowScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun getManagerIndexAtY(rawY: Float): Int {
        val loc = IntArray(2)
        for (i in managerRowViews.indices) {
            managerRowViews[i].getLocationOnScreen(loc)
            if (rawY < loc[1] + managerRowViews[i].height) return i
        }
        return (managerRowViews.size - 1).coerceAtLeast(0)
    }

    private fun swapManagerRows(from: Int, to: Int) {
        managerOrder.add(to, managerOrder.removeAt(from))
        managerRowViews.add(to, managerRowViews.removeAt(from))
        val container = managerRowContainer ?: return
        container.removeAllViews()
        managerRowViews.forEach { container.addView(it) }
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private fun navigateToPane(newIndex: Int) {
        val goingRight = newIndex > currentPane
        panes[currentPane].onPaused()
        viewFlipper.inAnimation  = slideIn(fromRight = goingRight)
        viewFlipper.outAnimation = slideOut(toLeft   = goingRight)
        currentPane = newIndex
        if (goingRight) viewFlipper.showNext() else viewFlipper.showPrevious()
        refreshTitleBar()
        panes[currentPane].onResumed()
    }

    private fun handleKey(keyCode: Int): Boolean {
        val cancelKey = prefs.getInt(KEY_CANCEL, DEFAULT_KEY_CANCEL)

        // While pane manager is open, only cancel key is handled (to exit manager)
        if (inPaneManager) {
            return if (keyCode == cancelKey || keyCode == SECONDARY_KEY_CANCEL) {
                exitPaneManager(); true
            } else false
        }

        val rightKey  = prefs.getInt(KEY_RIGHT,  DEFAULT_KEY_RIGHT)
        val leftKey   = prefs.getInt(KEY_LEFT,   DEFAULT_KEY_LEFT)
        val upKey     = prefs.getInt(KEY_UP,     DEFAULT_KEY_UP)
        val downKey   = prefs.getInt(KEY_DOWN,   DEFAULT_KEY_DOWN)
        val enterKey  = prefs.getInt(KEY_ENTER,  DEFAULT_KEY_ENTER)

        val pane = panes[currentPane]

        return when (keyCode) {
            rightKey -> { if (currentPane < panes.size - 1) navigateToPane(currentPane + 1); true }
            leftKey  -> { if (currentPane > 0)             navigateToPane(currentPane - 1); true }
            upKey    -> { pane.onUp();    true }
            downKey  -> { pane.onDown();  true }
            enterKey, SECONDARY_KEY_ENTER  -> { pane.onEnter();  true }
            cancelKey, SECONDARY_KEY_CANCEL -> {
                if (!pane.onCancel()) hideOverlay()
                true
            }
            else -> false
        }
    }

    // =========================================================================
    // Animation helpers
    // =========================================================================

    private fun slideIn(fromRight: Boolean): TranslateAnimation =
        TranslateAnimation(
            Animation.RELATIVE_TO_PARENT, if (fromRight) 1f else -1f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f
        ).apply { duration = 220; interpolator = DecelerateInterpolator() }

    private fun slideOut(toLeft: Boolean): TranslateAnimation =
        TranslateAnimation(
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, if (toLeft) -1f else 1f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f
        ).apply { duration = 220; interpolator = AccelerateInterpolator() }

    // =========================================================================
    // Notification
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_description) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_running))
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createLeftRoundedBackground(
        color: Int,
        radiusDp: Int,
        strokeWidthDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        val r = radiusDp.dp().toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setColor(color)
            if (strokeWidthDp > 0) setStroke(strokeWidthDp.dp(), strokeColor)
        }
    }
}
