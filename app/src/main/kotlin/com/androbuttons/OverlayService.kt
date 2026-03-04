package com.androbuttons

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "androbuttons_overlay"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "androbuttons_prefs"
        private const val KEY_UP = "key_up"
        private const val KEY_DOWN = "key_down"
        private const val KEY_LEFT = "key_left"
        private const val KEY_RIGHT = "key_right"
        private const val KEY_ENTER = "key_enter"
        private const val KEY_CANCEL = "key_cancel"
        private const val DEFAULT_KEY_UP = KeyEvent.KEYCODE_DPAD_UP
        private const val DEFAULT_KEY_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
        private const val DEFAULT_KEY_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        private const val DEFAULT_KEY_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        private const val DEFAULT_KEY_ENTER = KeyEvent.KEYCODE_ENTER
        private const val DEFAULT_KEY_CANCEL = KeyEvent.KEYCODE_ESCAPE
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // Pane state
    private var currentPane = 0
    private val paneCount = 3

    // Media player state
    private var mediaCtrlIndex = 0   // 0=Prev, 1=Play/Pause, 2=Next
    private var isPlaying = false

    // Stored view references for direct updates (no full rebuild)
    private lateinit var viewFlipper: ViewFlipper
    private val controlViews = arrayOfNulls<TextView>(3)
    private val indicatorViews = arrayOfNulls<TextView>(3)

    private val primaryColor = Color.parseColor("#1565C0")
    private val surfaceColor = Color.argb(230, 30, 30, 30)
    private val onSurfaceColor = Color.WHITE
    private val onPrimaryColor = Color.WHITE

    private val overlayWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.25f).toInt()

    private val overlayHeight: Int
        get() = (resources.displayMetrics.heightPixels * 0.95f).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_running))
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    // --- Overlay lifecycle ---

    private fun showOverlay() {
        removeOverlay()
        val view = buildRootView()
        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        windowParams = params
        view.translationX = overlayWidth.toFloat()
        windowManager.addView(view, params)
        overlayView = view
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { exitWithAnimation(); true } else false
        }
        view.requestFocus()
        view.animate()
            .translationX(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    // --- UI Building ---

    private fun buildRootView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 16.dp(), 12.dp(), 12.dp())
            background = createLeftRoundedBackground(surfaceColor, 16)
            elevation = 8.dp().toFloat()
        }

        container.addView(buildFlipperView())
        container.addView(buildPaneIndicator())

        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(keyCode) else false
        }

        return container
    }

    private fun buildFlipperView(): ViewFlipper {
        viewFlipper = ViewFlipper(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        viewFlipper.addView(buildMediaPlayerPane())
        viewFlipper.addView(buildEmptyPane("Pane 2"))
        viewFlipper.addView(buildEmptyPane("Pane 3"))
        viewFlipper.displayedChild = currentPane
        return viewFlipper
    }

    private fun buildMediaPlayerPane(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // "Now Playing" header label
        pane.addView(TextView(this).apply {
            text = "\u266A  Now Playing"
            textSize = 11f
            setTextColor(Color.argb(150, 255, 255, 255))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        })

        // Album art square (fills remaining height via weight)
        val albumArt = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(Color.argb(60, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f; bottomMargin = 12.dp() }
        }
        albumArt.addView(TextView(this).apply {
            text = "\uD83C\uDFB5"  // 🎵
            textSize = 40f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        })
        pane.addView(albumArt)

        // Song title
        pane.addView(TextView(this).apply {
            text = "Not Playing"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2.dp() }
        })

        // Artist name
        pane.addView(TextView(this).apply {
            text = "\u2014"   // em dash as placeholder
            textSize = 12f
            setTextColor(Color.argb(160, 255, 255, 255))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        })

        // Progress track (static visual, no actual seek)
        val progressTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 4.dp()
            ).apply { bottomMargin = 4.dp() }
            background = createRoundedBackground(Color.argb(60, 255, 255, 255), 2)
        }
        progressTrack.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { weight = 0f }
            background = createRoundedBackground(primaryColor, 2)
        })
        pane.addView(progressTrack)

        // Time row: 0:00 ··· 0:00
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }
        timeRow.addView(TextView(this).apply {
            text = "0:00"
            textSize = 10f
            setTextColor(Color.argb(130, 255, 255, 255))
        })
        timeRow.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0).apply { weight = 1f }
        })
        timeRow.addView(TextView(this).apply {
            text = "0:00"
            textSize = 10f
            setTextColor(Color.argb(130, 255, 255, 255))
        })
        pane.addView(timeRow)

        // Playback controls: ⏮  ▶/⏸  ⏭
        pane.addView(buildMediaControls())

        return pane
    }

    private fun buildMediaControls(): LinearLayout {
        val icons = listOf("\u23EE", if (isPlaying) "\u23F8" else "\u25B6", "\u23ED")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        icons.forEachIndexed { i, icon ->
            val isSelected = i == mediaCtrlIndex
            val btn = TextView(this).apply {
                text = icon
                textSize = 26f
                setTextColor(if (isSelected) onPrimaryColor else onSurfaceColor)
                gravity = Gravity.CENTER
                minimumHeight = 44.dp()
                background = if (isSelected) createRoundedBackground(primaryColor, 8) else null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    marginStart = 4.dp()
                    marginEnd = 4.dp()
                }
            }
            controlViews[i] = btn
            row.addView(btn)
        }
        return row
    }

    private fun buildEmptyPane(label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(TextView(this@OverlayService).apply {
                text = label
                textSize = 14f
                setTextColor(Color.argb(128, 255, 255, 255))
                gravity = Gravity.CENTER
            })
        }
    }

    private fun buildPaneIndicator(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp()
                bottomMargin = 4.dp()
            }
        }
        for (i in 0 until paneCount) {
            val isActive = i == currentPane
            val dot = TextView(this).apply {
                text = if (isActive) "\u25CF" else "\u25CB"   // ● or ○
                textSize = 10f
                setTextColor(if (isActive) Color.WHITE else Color.argb(100, 255, 255, 255))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 8.dp()
                    marginEnd = 8.dp()
                }
            }
            indicatorViews[i] = dot
            row.addView(dot)
        }
        return row
    }

    // --- Navigation ---

    private fun handleKey(keyCode: Int): Boolean {
        val rightKey = prefs.getInt(KEY_RIGHT, DEFAULT_KEY_RIGHT)
        val leftKey  = prefs.getInt(KEY_LEFT,  DEFAULT_KEY_LEFT)
        val upKey    = prefs.getInt(KEY_UP,    DEFAULT_KEY_UP)
        val downKey  = prefs.getInt(KEY_DOWN,  DEFAULT_KEY_DOWN)
        val enterKey = prefs.getInt(KEY_ENTER, DEFAULT_KEY_ENTER)
        val cancelKey= prefs.getInt(KEY_CANCEL,DEFAULT_KEY_CANCEL)

        return when (keyCode) {
            rightKey -> {
                if (currentPane < paneCount - 1) {
                    viewFlipper.inAnimation  = slideIn(fromRight = true)
                    viewFlipper.outAnimation = slideOut(toLeft = true)
                    currentPane++
                    viewFlipper.showNext()
                    refreshIndicator()
                }
                true
            }
            leftKey -> {
                if (currentPane > 0) {
                    viewFlipper.inAnimation  = slideIn(fromRight = false)
                    viewFlipper.outAnimation = slideOut(toLeft = false)
                    currentPane--
                    viewFlipper.showPrevious()
                    refreshIndicator()
                }
                true
            }
            upKey -> {
                if (currentPane == 0) {
                    mediaCtrlIndex = (mediaCtrlIndex - 1 + 3) % 3
                    refreshMediaControls()
                }
                true
            }
            downKey -> {
                if (currentPane == 0) {
                    mediaCtrlIndex = (mediaCtrlIndex + 1) % 3
                    refreshMediaControls()
                }
                true
            }
            enterKey -> {
                if (currentPane == 0) activateMediaControl()
                true
            }
            cancelKey -> {
                exitWithAnimation()
                true
            }
            else -> false
        }
    }

    private fun activateMediaControl() {
        if (mediaCtrlIndex == 1) {
            isPlaying = !isPlaying
            controlViews[1]?.text = if (isPlaying) "\u23F8" else "\u25B6"
        }
        // Prev (0) and Next (2): reserved for media key dispatch in a future update
    }

    private fun refreshMediaControls() {
        controlViews.forEachIndexed { i, view ->
            val isSelected = i == mediaCtrlIndex
            view?.setTextColor(if (isSelected) onPrimaryColor else onSurfaceColor)
            view?.background = if (isSelected) createRoundedBackground(primaryColor, 8) else null
        }
    }

    private fun refreshIndicator() {
        indicatorViews.forEachIndexed { i, view ->
            val isActive = i == currentPane
            view?.text = if (isActive) "\u25CF" else "\u25CB"
            view?.setTextColor(if (isActive) Color.WHITE else Color.argb(100, 255, 255, 255))
        }
    }

    // --- Animation helpers ---

    private fun slideIn(fromRight: Boolean): TranslateAnimation =
        TranslateAnimation(
            Animation.RELATIVE_TO_PARENT, if (fromRight) 1f else -1f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f
        ).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
        }

    private fun slideOut(toLeft: Boolean): TranslateAnimation =
        TranslateAnimation(
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, if (toLeft) -1f else 1f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f
        ).apply {
            duration = 220
            interpolator = AccelerateInterpolator()
        }

    private fun exitWithAnimation() {
        val view = overlayView ?: run { stopSelf(); return }
        view.animate()
            .translationX(view.width.toFloat())
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { stopSelf() }
            .start()
    }

    // --- Utilities ---

    private fun Int.dp(): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun createRoundedBackground(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dp().toFloat()
            setColor(color)
        }
    }

    private fun createLeftRoundedBackground(color: Int, radiusDp: Int): GradientDrawable {
        val r = radiusDp.dp().toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setColor(color)
        }
    }
}
