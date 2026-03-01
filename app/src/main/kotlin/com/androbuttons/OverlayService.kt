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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Space
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
        private const val DEFAULT_KEY_ENTER = KeyEvent.KEYCODE_ENTER
        private const val DEFAULT_KEY_CANCEL = KeyEvent.KEYCODE_ESCAPE
    }

    private data class MenuItem(
        val id: String,
        val label: String,
        val icon: String,
        val hasSubMenu: Boolean = false
    )

    private enum class MenuScreen { MAIN, SETTINGS, KEY_MAPPING, DASHBOARD, CREATE_NOTE }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlayView: View? = null

    private var currentScreen = MenuScreen.MAIN
    private var selectedIndex = 0
    private var awaitingKeyForAction: String? = null

    private val mainMenuItems = listOf(
        MenuItem("settings", "Settings", "\u2699", hasSubMenu = true),
        MenuItem("dashboard", "Dashboard", "\uD83D\uDCCA", hasSubMenu = true),
        MenuItem("create_note", "Create Note", "\uD83D\uDCDD", hasSubMenu = true),
        MenuItem("exit", "Exit", "\u2716")
    )

    private val settingsMenuItems = listOf(
        MenuItem("key_mapping", "Key Mapping", "\u2328", hasSubMenu = true),
        MenuItem("back", "Back", "\u2190")
    )

    private val stubMenuItems = listOf(
        MenuItem("back", "Back", "\u2190")
    )

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
        view.requestFocus()
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

    private fun buildMenuView(): View {
        val surfaceColor = Color.argb(230, 30, 30, 30)
        val onSurfaceColor = Color.WHITE
        val primaryColor = Color.parseColor("#1565C0")
        val onPrimaryColor = Color.WHITE
        val dividerColor = Color.argb(50, 255, 255, 255)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = 280.dp()
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
            background = createRoundedBackground(surfaceColor, 16)
            elevation = 8.dp().toFloat()
        }

        val titleText = when (currentScreen) {
            MenuScreen.MAIN -> getString(R.string.menu_title)
            MenuScreen.SETTINGS -> getString(R.string.menu_settings)
            MenuScreen.KEY_MAPPING -> getString(R.string.menu_key_mapping)
            MenuScreen.DASHBOARD -> getString(R.string.menu_dashboard)
            MenuScreen.CREATE_NOTE -> getString(R.string.menu_create_note)
        }
        container.addView(buildTitleView(titleText, onSurfaceColor))
        container.addView(buildDivider(dividerColor))

        when (currentScreen) {
            MenuScreen.MAIN -> buildStandardMenu(container, mainMenuItems, primaryColor, onSurfaceColor, onPrimaryColor)
            MenuScreen.SETTINGS -> buildStandardMenu(container, settingsMenuItems, primaryColor, onSurfaceColor, onPrimaryColor)
            MenuScreen.KEY_MAPPING -> buildKeyMappingMenu(container, primaryColor, onSurfaceColor, onPrimaryColor, dividerColor)
            MenuScreen.DASHBOARD, MenuScreen.CREATE_NOTE -> buildStubMenu(container, primaryColor, onSurfaceColor, onPrimaryColor)
        }

        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(keyCode) else false
        }

        return container
    }

    private fun buildTitleView(title: String, textColor: Int): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(textColor)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(4.dp(), 0, 0, 8.dp())
        }
    }

    private fun buildDivider(color: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
            ).apply {
                topMargin = 4.dp()
                bottomMargin = 8.dp()
            }
            setBackgroundColor(color)
        }
    }

    private fun buildStandardMenu(
        container: LinearLayout,
        items: List<MenuItem>,
        primaryColor: Int,
        onSurfaceColor: Int,
        onPrimaryColor: Int
    ) {
        items.forEachIndexed { index, menuItem ->
            val isSelected = index == selectedIndex
            val bgColor = if (isSelected) primaryColor else Color.TRANSPARENT
            val fgColor = if (isSelected) onPrimaryColor else onSurfaceColor

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 44.dp()
                setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
                background = createRoundedBackground(bgColor, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 2.dp()
                    bottomMargin = 2.dp()
                }
            }

            row.addView(TextView(this).apply {
                text = menuItem.icon
                textSize = 16f
                setTextColor(fgColor)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(28.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            row.addView(Space(this).apply {
                layoutParams = LinearLayout.LayoutParams(8.dp(), 0)
            })

            row.addView(TextView(this).apply {
                text = menuItem.label
                textSize = 15f
                setTextColor(fgColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (menuItem.hasSubMenu) {
                row.addView(TextView(this).apply {
                    text = "\u203A"
                    textSize = 18f
                    setTextColor(fgColor)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(24.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }

            row.setOnClickListener { selectedIndex = index; activateSelected() }
            container.addView(row)
        }
    }

    private fun buildKeyMappingMenu(
        container: LinearLayout,
        primaryColor: Int,
        onSurfaceColor: Int,
        onPrimaryColor: Int,
        dividerColor: Int
    ) {
        val backIndex = keyMappingActions.size

        keyMappingActions.forEachIndexed { index, (prefKey, label) ->
            val isSelected = index == selectedIndex
            val bgColor = if (isSelected) primaryColor else Color.TRANSPARENT
            val fgColor = if (isSelected) onPrimaryColor else onSurfaceColor
            val isDetecting = awaitingKeyForAction == prefKey

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 44.dp()
                setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
                background = createRoundedBackground(bgColor, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 2.dp()
                    bottomMargin = 2.dp()
                }
            }

            if (isDetecting) {
                row.addView(TextView(this).apply {
                    text = "$label:  ${getString(R.string.key_mapping_prompt)}"
                    textSize = 14f
                    setTextColor(Color.YELLOW)
                    setTypeface(null, Typeface.ITALIC)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            } else {
                row.addView(TextView(this).apply {
                    text = "$label:"
                    textSize = 14f
                    setTextColor(fgColor)
                    setTypeface(null, Typeface.BOLD)
                })

                row.addView(Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(8.dp(), 0)
                })

                row.addView(TextView(this).apply {
                    text = keyCodeLabel(prefs.getInt(prefKey, defaultForKey(prefKey)))
                    textSize = 13f
                    setTextColor(fgColor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                row.addView(TextView(this).apply {
                    text = "[${getString(R.string.menu_detect)}]"
                    textSize = 12f
                    setTextColor(if (isSelected) onPrimaryColor else Color.argb(180, 255, 255, 255))
                })
            }

            row.setOnClickListener { selectedIndex = index; activateSelected() }
            container.addView(row)
        }

        container.addView(buildDivider(dividerColor))

        // Back item
        val isBackSelected = selectedIndex == backIndex
        val backBg = if (isBackSelected) primaryColor else Color.TRANSPARENT
        val backFg = if (isBackSelected) onPrimaryColor else onSurfaceColor

        val backRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 44.dp()
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            background = createRoundedBackground(backBg, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 2.dp()
                bottomMargin = 2.dp()
            }
        }

        backRow.addView(TextView(this).apply {
            text = "\u2190"
            textSize = 16f
            setTextColor(backFg)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(28.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        backRow.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp(), 0)
        })

        backRow.addView(TextView(this).apply {
            text = getString(R.string.menu_back)
            textSize = 15f
            setTextColor(backFg)
        })

        backRow.setOnClickListener { selectedIndex = backIndex; activateSelected() }
        container.addView(backRow)
    }

    private fun buildStubMenu(
        container: LinearLayout,
        primaryColor: Int,
        onSurfaceColor: Int,
        onPrimaryColor: Int
    ) {
        container.addView(TextView(this).apply {
            text = getString(R.string.stub_coming_soon)
            textSize = 14f
            setTextColor(Color.argb(128, 255, 255, 255))
            setPadding(12.dp(), 16.dp(), 12.dp(), 16.dp())
            gravity = Gravity.CENTER
        })

        buildStandardMenu(container, stubMenuItems, primaryColor, onSurfaceColor, onPrimaryColor)
    }

    // --- Navigation Logic ---

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

        val itemCount = when (currentScreen) {
            MenuScreen.MAIN -> mainMenuItems.size
            MenuScreen.SETTINGS -> settingsMenuItems.size
            MenuScreen.KEY_MAPPING -> keyMappingActions.size + 1
            MenuScreen.DASHBOARD, MenuScreen.CREATE_NOTE -> stubMenuItems.size
        }

        return when (keyCode) {
            upKey -> {
                selectedIndex = (selectedIndex - 1 + itemCount) % itemCount
                refreshOverlay()
                true
            }
            downKey -> {
                selectedIndex = (selectedIndex + 1) % itemCount
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
            MenuScreen.MAIN -> {
                when (mainMenuItems[selectedIndex].id) {
                    "settings" -> navigateTo(MenuScreen.SETTINGS)
                    "dashboard" -> navigateTo(MenuScreen.DASHBOARD)
                    "create_note" -> navigateTo(MenuScreen.CREATE_NOTE)
                    "exit" -> stopSelf()
                }
            }
            MenuScreen.SETTINGS -> {
                when (settingsMenuItems[selectedIndex].id) {
                    "key_mapping" -> navigateTo(MenuScreen.KEY_MAPPING)
                    "back" -> goBack()
                }
            }
            MenuScreen.KEY_MAPPING -> {
                if (selectedIndex < keyMappingActions.size) {
                    awaitingKeyForAction = keyMappingActions[selectedIndex].first
                    refreshOverlay()
                } else {
                    goBack()
                }
            }
            MenuScreen.DASHBOARD, MenuScreen.CREATE_NOTE -> {
                if (stubMenuItems[selectedIndex].id == "back") {
                    goBack()
                }
            }
        }
    }

    private fun navigateTo(screen: MenuScreen) {
        currentScreen = screen
        selectedIndex = 0
        refreshOverlay()
    }

    private fun goBack() {
        when (currentScreen) {
            MenuScreen.MAIN -> { /* already at root */ }
            MenuScreen.SETTINGS -> {
                currentScreen = MenuScreen.MAIN
                selectedIndex = 0
                refreshOverlay()
            }
            MenuScreen.KEY_MAPPING -> {
                currentScreen = MenuScreen.SETTINGS
                selectedIndex = 0
                refreshOverlay()
            }
            MenuScreen.DASHBOARD -> {
                currentScreen = MenuScreen.MAIN
                selectedIndex = 1
                refreshOverlay()
            }
            MenuScreen.CREATE_NOTE -> {
                currentScreen = MenuScreen.MAIN
                selectedIndex = 2
                refreshOverlay()
            }
        }
    }

    private fun refreshOverlay() {
        showOverlay()
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
