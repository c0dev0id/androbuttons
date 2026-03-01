package com.androbuttons

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
        private const val DEFAULT_KEY_ENTER = KeyEvent.KEYCODE_DPAD_CENTER
        private const val DEFAULT_KEY_CANCEL = KeyEvent.KEYCODE_BACK
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlayView: View? = null

    private enum class MenuScreen { MAIN, SETTINGS, KEY_MAPPING }

    private var currentScreen = MenuScreen.MAIN
    private var selectedIndex = 0
    private var awaitingKeyForAction: String? = null

    private val mainMenuItems = listOf("Save Location", "Settings", "Exit")
    private val settingsMenuItems = listOf("Key Mapping", "Back")
    private val keyMappingActions = listOf(
        Pair(KEY_UP, "Up"),
        Pair(KEY_DOWN, "Down"),
        Pair(KEY_LEFT, "Left"),
        Pair(KEY_RIGHT, "Right"),
        Pair(KEY_ENTER, "Enter"),
        Pair(KEY_CANCEL, "Cancel")
    )

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

    private fun showOverlay() {
        removeOverlay()
        val view = buildMenuView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun buildMenuView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.argb(220, 30, 30, 30))
        }

        val items: List<String> = when (currentScreen) {
            MenuScreen.MAIN -> mainMenuItems
            MenuScreen.SETTINGS -> settingsMenuItems
            MenuScreen.KEY_MAPPING -> keyMappingActions.map { (key, label) ->
                "$label: ${keyCodeLabel(prefs.getInt(key, defaultForKey(key)))}"
            }
        }

        val title = TextView(this).apply {
            text = when (currentScreen) {
                MenuScreen.MAIN -> getString(R.string.app_name)
                MenuScreen.SETTINGS -> getString(R.string.menu_settings)
                MenuScreen.KEY_MAPPING -> getString(R.string.menu_key_mapping)
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        container.addView(title)

        if (awaitingKeyForAction != null) {
            val prompt = TextView(this).apply {
                text = getString(R.string.key_mapping_prompt)
                setTextColor(Color.YELLOW)
                textSize = 13f
            }
            container.addView(prompt)
        } else {
            items.forEachIndexed { index, label ->
                val item = TextView(this).apply {
                    text = label
                    textSize = 16f
                    setPadding(16, 20, 16, 20)
                    if (index == selectedIndex) {
                        setTextColor(Color.BLACK)
                        setBackgroundColor(Color.WHITE)
                    } else {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.TRANSPARENT)
                    }
                }
                container.addView(item)
            }
        }

        container.isFocusable = true
        container.isFocusableInTouchMode = true

        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                handleKey(keyCode)
            } else {
                false
            }
        }

        container.requestFocus()
        return container
    }

    private fun handleKey(keyCode: Int): Boolean {
        if (awaitingKeyForAction != null) {
            val action = awaitingKeyForAction!!
            prefs.edit().putInt(action, keyCode).apply()
            awaitingKeyForAction = null
            refreshOverlay()
            return true
        }

        val upKey = prefs.getInt(KEY_UP, DEFAULT_KEY_UP)
        val downKey = prefs.getInt(KEY_DOWN, DEFAULT_KEY_DOWN)
        val enterKey = prefs.getInt(KEY_ENTER, DEFAULT_KEY_ENTER)
        val cancelKey = prefs.getInt(KEY_CANCEL, DEFAULT_KEY_CANCEL)

        val items: List<String> = when (currentScreen) {
            MenuScreen.MAIN -> mainMenuItems
            MenuScreen.SETTINGS -> settingsMenuItems
            MenuScreen.KEY_MAPPING -> keyMappingActions.map { it.second }
        }

        return when (keyCode) {
            upKey -> {
                selectedIndex = (selectedIndex - 1 + items.size) % items.size
                refreshOverlay()
                true
            }
            downKey -> {
                selectedIndex = (selectedIndex + 1) % items.size
                refreshOverlay()
                true
            }
            enterKey -> {
                activateSelected()
                true
            }
            cancelKey -> {
                goBack()
                true
            }
            else -> false
        }
    }

    private fun activateSelected() {
        when (currentScreen) {
            MenuScreen.MAIN -> when (selectedIndex) {
                0 -> { /* Save Location - to be implemented */ }
                1 -> {
                    currentScreen = MenuScreen.SETTINGS
                    selectedIndex = 0
                    refreshOverlay()
                }
                2 -> stopSelf()
            }
            MenuScreen.SETTINGS -> when (selectedIndex) {
                0 -> {
                    currentScreen = MenuScreen.KEY_MAPPING
                    selectedIndex = 0
                    refreshOverlay()
                }
                1 -> goBack()
            }
            MenuScreen.KEY_MAPPING -> {
                awaitingKeyForAction = keyMappingActions[selectedIndex].first
                refreshOverlay()
            }
        }
    }

    private fun goBack() {
        when (currentScreen) {
            MenuScreen.MAIN -> { /* already at root */ }
            MenuScreen.SETTINGS -> {
                currentScreen = MenuScreen.MAIN
                selectedIndex = 1
                refreshOverlay()
            }
            MenuScreen.KEY_MAPPING -> {
                currentScreen = MenuScreen.SETTINGS
                selectedIndex = 0
                refreshOverlay()
            }
        }
    }

    private fun refreshOverlay() {
        showOverlay()
    }

    private fun defaultForKey(prefKey: String): Int = when (prefKey) {
        KEY_UP -> DEFAULT_KEY_UP
        KEY_DOWN -> DEFAULT_KEY_DOWN
        KEY_LEFT -> DEFAULT_KEY_LEFT
        KEY_RIGHT -> DEFAULT_KEY_RIGHT
        KEY_ENTER -> DEFAULT_KEY_ENTER
        KEY_CANCEL -> DEFAULT_KEY_CANCEL
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private fun keyCodeLabel(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace("_", " ")
}
