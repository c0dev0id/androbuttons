package com.androbuttons.panes.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.dpWith

/**
 * Full-screen activity that lets the user pick an Android app widget to add
 * to a widget pane. Handles the bind-permission and optional configure flow,
 * then appends the new AppWidget ID to the pane's stored list.
 *
 * Started via [android.content.Context.startActivity] with FLAG_ACTIVITY_NEW_TASK
 * from [WidgetPane] when the user taps "➕ Add Widget".
 */
class WidgetPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PANE_ID = "pane_id"
        private const val PREFS_NAME = "androbuttons_prefs"
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2
    }

    private lateinit var paneId: String
    private lateinit var prefs: SharedPreferences
    private var pendingAppWidgetId = -1
    // Guard against saveAndFinish() / releaseAndFinish() being invoked more than once
    // (can happen on some ROMs when a stale REQUEST_BIND result arrives after the
    // configure-activity result has already been processed).
    private var pendingHandled = false

    private fun Int.dp() = dpWith(this@WidgetPickerActivity)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        paneId = intent.getStringExtra(EXTRA_PANE_ID) ?: run { finish(); return }
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        pendingAppWidgetId = savedInstanceState?.getInt("pendingAppWidgetId", -1) ?: -1
        pendingHandled = savedInstanceState?.getBoolean("pendingHandled", false) ?: false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.surface)
        }

        // Title bar
        root.addView(TextView(this).apply {
            text = "Add Android Widget"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Theme.header)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        val providers = AppWidgetManager.getInstance(this).getInstalledProviders()
            .sortedWith(compareBy(
                { loadLabel(it) },
                { it.provider.className }
            ))

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        }

        if (providers.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No Android widgets found on this device."
                textSize = 13f
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(16.dp(), 40.dp(), 16.dp(), 40.dp())
            })
        } else {
            providers.forEach { info ->
                listContainer.addView(buildProviderRow(info))
            }
        }

        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listContainer)
        })

        root.addView(buildCancelButton())
        setContentView(root)
    }

    private fun buildProviderRow(info: AppWidgetProviderInfo): LinearLayout {
        val label = loadLabel(info)

        val icon = ImageView(this).apply {
            try {
                val drawable = info.loadIcon(this@WidgetPickerActivity, resources.displayMetrics.densityDpi)
                setImageDrawable(drawable)
            } catch (_: Exception) { /* no icon */ }
            val size = 36.dp()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 12.dp() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textBlock.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        // Widget description (API 31+) or package name as secondary line
        val secondary = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            info.loadDescription(this)?.toString()?.takeIf { it.isNotBlank() }
        } else null
        val subtitleText = secondary ?: info.provider.packageName

        textBlock.addView(TextView(this).apply {
            text = subtitleText
            textSize = 11f
            setTextColor(Theme.textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2.dp() }
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 14.dp(), 10.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6.dp().toFloat()
                setColor(Theme.inactiveBg)
            }
            isClickable = true
            addView(icon)
            addView(textBlock)
            setOnClickListener { startBind(info) }
        }
        return row
    }

    private fun buildCancelButton() = TextView(this).apply {
        text = "Cancel"
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(Theme.textSecondary)
        background = actionButtonBg(Theme.inactiveBg, this@WidgetPickerActivity)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 10.dp(); rightMargin = 10.dp(); topMargin = 6.dp(); bottomMargin = 10.dp() }
        setPadding(12.dp(), 16.dp(), 12.dp(), 16.dp())
        isClickable = true
        setOnClickListener { finish() }
    }

    private fun loadLabel(info: AppWidgetProviderInfo): String =
        try { info.loadLabel(packageManager).takeIf { it.isNotBlank() } ?: info.provider.className }
        catch (_: Exception) { info.provider.className }

    // ---- Bind flow ----------------------------------------------------------

    private fun startBind(info: AppWidgetProviderInfo) {
        val host = AppWidgetHostManager.getHost(this)
        pendingAppWidgetId = host.allocateAppWidgetId()

        val manager = AppWidgetManager.getInstance(this)
        if (manager.bindAppWidgetIdIfAllowed(pendingAppWidgetId, info.provider)) {
            // Already allowed (rare for third-party apps); proceed directly.
            onBound(info)
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingAppWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_BIND)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_BIND -> {
                if (resultCode == RESULT_OK) {
                    val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(pendingAppWidgetId)
                    if (info != null) onBound(info)
                    else {
                        releaseAndFinish()
                    }
                } else {
                    releaseAndFinish()
                }
            }
            REQUEST_CONFIGURE -> {
                // Always save after a configure activity runs, regardless of result code.
                // Lawnchair/Launcher3 does the same: many providers incorrectly return
                // RESULT_CANCELED even after successful configuration, and a provider crash
                // also surfaces as RESULT_CANCELED. Discarding on cancel would silently lose
                // the widget in both cases.
                saveAndFinish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pendingAppWidgetId", pendingAppWidgetId)
        outState.putBoolean("pendingHandled", pendingHandled)
    }

    private fun onBound(info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            // Use the host API to launch the configure activity. This routes through
            // the system's AppWidgetServiceImpl which creates an IntentSender with
            // the proper grants — bypassing Android 11+ package-visibility restrictions
            // that break a manually constructed ACTION_APPWIDGET_CONFIGURE intent.
            try {
                AppWidgetHostManager.getHost(this)
                    .startAppWidgetConfigureActivityForResult(this, pendingAppWidgetId, 0, REQUEST_CONFIGURE, null)
                return  // wait for onActivityResult(REQUEST_CONFIGURE)
            } catch (_: Exception) {
                // The configure activity is declared but could not be launched. We cannot
                // add the widget without configuration — the provider won't send RemoteViews
                // until configuration completes, so the widget would be stuck in a permanent
                // loading state. Release the allocated ID and inform the user.
                android.widget.Toast.makeText(
                    this,
                    "Widget could not be configured (configure screen unavailable)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                releaseAndFinish()
                return
            }
        }
        saveAndFinish()
    }

    private fun saveAndFinish() {
        if (pendingHandled) return
        pendingHandled = true
        val current = prefs.getString("${paneId}_appwidget_ids", null)
        val existing = current?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        if (pendingAppWidgetId !in existing) {
            val newList = if (existing.isEmpty()) "$pendingAppWidgetId"
                          else "$current,$pendingAppWidgetId"
            prefs.edit().putString("${paneId}_appwidget_ids", newList).apply()
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun releaseAndFinish() {
        if (pendingHandled) return
        pendingHandled = true
        if (pendingAppWidgetId != -1) {
            AppWidgetHostManager.getHost(this).deleteAppWidgetId(pendingAppWidgetId)
            pendingAppWidgetId = -1
        }
        finish()
    }
}
