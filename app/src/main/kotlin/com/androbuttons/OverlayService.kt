package com.androbuttons

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.core.app.NotificationCompat
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.dpWith
import com.androbuttons.panes.apps.AppsPane
import com.androbuttons.panes.markers.MarkersPane
import com.androbuttons.panes.music.MusicPane
import com.androbuttons.panes.pointers.PointersPane
import com.androbuttons.panes.sensors.SensorsPane
import com.androbuttons.panes.system.SystemPane

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
        get() = (resources.displayMetrics.widthPixels * (1f / 3f)).toInt()

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
        // Instantiate panes once here; their views are built lazily in showOverlay()
        panes = listOf(
            MusicPane(bridge = this),
            AppsPane(bridge = this),
            SensorsPane(bridge = this),
            MarkersPane(bridge = this),
            PointersPane(bridge = this),
            SystemPane(bridge = this)
        )
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
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
    }

    // =========================================================================
    // ServiceBridge implementation
    // =========================================================================

    override val context: Context get() = this

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
        container.addView(buildTitleBar())
        container.addView(buildFlipperView())
        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(keyCode) else false
        }
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { hideOverlay(); true } else false
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
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleArrowLeft = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_left)
            layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
            setColorFilter(if (currentPane > 0) Theme.primary else Theme.inactiveBg)
        }
        titleText = TextView(this).apply {
            text = panes[currentPane].title; textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER_HORIZONTAL
        }
        titleArrowRight = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_right)
            layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
            setColorFilter(if (currentPane < panes.size - 1) Theme.primary else Theme.inactiveBg)
        }

        titleLeftZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(12.dp(), 12.dp(), 12.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            alpha = if (currentPane > 0) 1f else 0.4f
            addView(titleArrowLeft)
            setOnClickListener { if (currentPane > 0) navigateToPane(currentPane - 1) }
        }

        val centerZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 6.dp())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 2f }
            addView(titleText)
        }

        titleRightZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(12.dp(), 12.dp(), 12.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            alpha = if (currentPane < panes.size - 1) 1f else 0.4f
            addView(titleArrowRight)
            setOnClickListener { if (currentPane < panes.size - 1) navigateToPane(currentPane + 1) }
        }

        fun verticalDivider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1.dp(), 24.dp()).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
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
            setPadding(0, 0, 0, 8.dp())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
        wrapper.addView(dotsRow)

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
        val rightKey  = prefs.getInt(KEY_RIGHT,  DEFAULT_KEY_RIGHT)
        val leftKey   = prefs.getInt(KEY_LEFT,   DEFAULT_KEY_LEFT)
        val upKey     = prefs.getInt(KEY_UP,     DEFAULT_KEY_UP)
        val downKey   = prefs.getInt(KEY_DOWN,   DEFAULT_KEY_DOWN)
        val enterKey  = prefs.getInt(KEY_ENTER,  DEFAULT_KEY_ENTER)
        val cancelKey = prefs.getInt(KEY_CANCEL, DEFAULT_KEY_CANCEL)

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
