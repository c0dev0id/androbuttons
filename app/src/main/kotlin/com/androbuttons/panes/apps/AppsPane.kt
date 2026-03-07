package com.androbuttons.panes.apps

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith

class AppsPane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "Apps"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private companion object {
        const val KEY_SELECTED_APPS = "selected_apps"

        val DEFAULT_APPS = listOf(
            "Voice Note"    to "com.voicenotes.main",
            "Ridelink"      to "otp.systems.ridelink",
            "Cardo Connect" to "com.cardo.smartset",
            "Sena +Mesh"    to "com.sena.plusmesh",
            "Telegram"      to "org.telegram.messenger",
            "WhatsApp"      to "com.whatsapp",
            "Discord"       to "com.discord",
            "Blitzer.de"    to "de.blitzer.plus",
            "MeteoBlue"     to "com.meteoblue.droid",
            "PACE Drive"    to "car.pace.drive",
            "ryd"           to "com.thinxnet.native_tanktaler_android",
            "Google Drive"  to "com.google.android.apps.docs",
            "Google Photos" to "com.google.android.apps.photos",
            "Google Maps"   to "com.google.android.apps.maps",
            "DMD2"          to "com.thorkracing.dmd2launcher"
        )
    }

    // ---- State ---------------------------------------------------------------

    private data class AppEntry(val label: String, val packageName: String, val isInstalled: Boolean)

    private val appEntries    = mutableListOf<AppEntry>()
    private var appListIndex  = 0
    private var appScrollView: ScrollView? = null
    private val appButtonViews = mutableListOf<LinearLayout>()
    private var appButtonList: LinearLayout? = null
    private var sortingMode              = false
    private var sortDragIndex            = -1
    private var sortDragTargetIndex      = -1
    private var sortDragGhost: View?     = null
    private var sortDragGhostOffsetY     = 0f
    private var sortDragGhostBaseScreenY = 0f

    private var paneRoot: LinearLayout? = null

    // ---- PaneContent --------------------------------------------------------

    override fun buildView(): View {
        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
        }
        paneRoot = pane
        showAppsView()
        return pane
    }

    override fun onResumed() {
        appListIndex = 0
        refreshAppList()
    }

    override fun onUp(): Boolean {
        if (appButtonViews.isNotEmpty() && appListIndex > 0) {
            appListIndex--
            refreshAppList()
            scrollToSelectedApp()
        }
        return true
    }

    override fun onDown(): Boolean {
        if (appButtonViews.isNotEmpty() && appListIndex < appButtonViews.size - 1) {
            appListIndex++
            refreshAppList()
            scrollToSelectedApp()
        }
        return true
    }

    override fun onEnter(): Boolean {
        val entry = appEntries.getOrNull(appListIndex) ?: return true
        if (!sortingMode && entry.isInstalled) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(entry.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) { ctx.startActivity(intent); bridge.hideOverlay() }
        }
        return true
    }

    // ---- Apps view ----------------------------------------------------------

    private fun showAppsView() {
        val pane = paneRoot ?: return
        pane.removeAllViews()

        appEntries.clear()
        appEntries.addAll(loadSelectedApps().map { (label, pkg) -> AppEntry(label, pkg, true) })
        appListIndex = 0
        appButtonViews.clear()

        val buttonList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        appButtonList = buttonList
        appEntries.forEachIndexed { i, entry ->
            val btn = buildAppButton(entry, i == 0, i)
            appButtonViews.add(btn)
            buttonList.addView(btn)
        }

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(buttonList)
        }
        appScrollView = scrollView

        pane.addView(scrollView)
        pane.addView(TextView(ctx).apply {
            text = "Configure"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Theme.textSecondary)
            background = actionButtonBg(Theme.inactiveBg, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = true
            setOnClickListener { showConfigureView() }
        })
    }

    private fun showConfigureView() {
        val pane = paneRoot ?: return
        pane.removeAllViews()

        val selectedPkgs = loadSelectedApps().map { it.second }.toSet()
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installedApps = ctx.packageManager.queryIntentActivities(launchIntent, 0)
            .map { ri -> ri.activityInfo.applicationInfo.packageName to ri.loadLabel(ctx.packageManager).toString() }
            .distinctBy { it.first }
            .sortedWith(compareByDescending<Pair<String, String>> { it.first in selectedPkgs }.thenBy { it.second })

        val checkedState = mutableMapOf<String, Boolean>()
        installedApps.forEach { (pkg, _) -> checkedState[pkg] = pkg in selectedPkgs }

        fun makeCheckbox(checked: Boolean) = TextView(ctx).apply {
            val size = 24.dp()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = 12.dp() }
            gravity = Gravity.CENTER
            text = if (checked) "✓" else ""
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4.dp().toFloat()
                if (checked) setColor(Theme.primary) else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
            }
        }

        val rowContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        installedApps.forEach { (pkg, label) ->
            val checkbox = makeCheckbox(checkedState[pkg] == true)
            rowContainer.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 2.dp() }
                addView(checkbox)
                addView(TextView(ctx).apply {
                    text = label; textSize = 14f; setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                setOnClickListener {
                    val nowChecked = !(checkedState[pkg] ?: false)
                    checkedState[pkg] = nowChecked
                    (checkbox as TextView).text = if (nowChecked) "✓" else ""
                    checkbox.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 4.dp().toFloat()
                        if (nowChecked) setColor(Theme.primary) else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
                    }
                }
            })
        }

        pane.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rowContainer)
        })
        pane.addView(TextView(ctx).apply {
            text = "Save"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = actionButtonBg(Theme.primary, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = true
            setOnClickListener {
                val selected = installedApps
                    .filter { (pkg, _) -> checkedState[pkg] == true }
                    .map { (pkg, label) -> label to pkg }
                saveSelectedApps(selected)
                showAppsView()
            }
        })
    }

    // ---- App button ---------------------------------------------------------

    private fun buildAppButton(entry: AppEntry, isFocused: Boolean, index: Int): LinearLayout {
        val icon = try { ctx.packageManager.getApplicationIcon(entry.packageName) } catch (_: Exception) { null }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            background = buttonBg(isFocused, ctx)
            isClickable = true

            val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!sortingMode && entry.isInstalled) {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(entry.packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (intent != null) { ctx.startActivity(intent); bridge.hideOverlay() }
                    }
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    val currentIndex = appButtonViews.indexOf(this@apply)
                    if (currentIndex < 0) return
                    sortingMode = true
                    sortDragIndex = currentIndex
                    sortDragTargetIndex = currentIndex

                    // Capture finger offset from the row's top on screen
                    val rowLoc = IntArray(2)
                    this@apply.getLocationOnScreen(rowLoc)
                    sortDragGhostOffsetY = e.rawY - rowLoc[1]

                    // Compute ghost's natural screen Y: it will be placed at the bottom of the container
                    val container = appButtonList ?: return
                    val containerLoc = IntArray(2)
                    container.getLocationOnScreen(containerLoc)
                    sortDragGhostBaseScreenY = (containerLoc[1] + container.height).toFloat()

                    // Build a semi-transparent ghost copy of the row (no interaction)
                    val ghost = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 8.dp() }
                        background = buttonBg(true, ctx)
                        alpha = 0.5f
                        elevation = 8f
                        translationY = (rowLoc[1] - sortDragGhostBaseScreenY)
                        if (icon != null) {
                            addView(ImageView(ctx).apply {
                                setImageDrawable(icon)
                                val size = 36.dp()
                                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dp() }
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            })
                        }
                        addView(TextView(ctx).apply {
                            text = entry.label
                            textSize = 16f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(if (entry.isInstalled) Color.WHITE else Theme.textSecondary)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
                        })
                    }
                    sortDragGhost = ghost
                    container.addView(ghost)

                    // Hide the original row as a placeholder (keeps its space)
                    this@apply.visibility = View.INVISIBLE
                    appScrollView?.requestDisallowInterceptTouchEvent(true)
                }
            })

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN  -> { v.drawableHotspotChanged(event.x, event.y); v.isPressed = true }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                val handled = gestureDetector.onTouchEvent(event)
                if (sortingMode) {
                    when (event.action) {
                        MotionEvent.ACTION_MOVE -> {
                            val ghost = sortDragGhost
                            if (ghost != null) {
                                ghost.translationY = event.rawY - sortDragGhostOffsetY - sortDragGhostBaseScreenY
                            }
                            val newIdx = getIndexAtY(event.rawY)
                            if (newIdx != sortDragTargetIndex) {
                                sortDragTargetIndex = newIdx
                                appButtonViews.forEachIndexed { i, btn ->
                                    btn.background = if (i == newIdx && i != sortDragIndex) {
                                        buttonBg(true, ctx)
                                    } else {
                                        buttonBg(false, ctx)
                                    }
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            // Remove ghost
                            val container = appButtonList
                            sortDragGhost?.let { container?.removeView(it) }
                            sortDragGhost = null

                            // Restore original row visibility
                            v.visibility = View.VISIBLE

                            // Commit the move
                            val fromIdx = sortDragIndex
                            val toIdx = sortDragTargetIndex.coerceIn(0, (appEntries.size - 1).coerceAtLeast(0))
                            if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                                appEntries.add(toIdx, appEntries.removeAt(fromIdx))
                                appButtonViews.add(toIdx, appButtonViews.removeAt(fromIdx))
                            }

                            // Rebuild container child order
                            if (container != null) {
                                container.removeAllViews()
                                appButtonViews.forEach { container.addView(it) }
                            }

                            sortingMode = false
                            sortDragIndex = -1
                            sortDragTargetIndex = -1
                            appScrollView?.requestDisallowInterceptTouchEvent(false)
                            saveSelectedApps(appEntries.map { it.label to it.packageName })
                            refreshAppList()
                        }
                    }
                    true
                } else handled
            }

            if (icon != null) {
                addView(ImageView(ctx).apply {
                    setImageDrawable(icon)
                    val size = 36.dp()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dp() }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }
            addView(TextView(ctx).apply {
                text = entry.label
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (entry.isInstalled) Color.WHITE else Theme.textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            })
        }
    }

    // ---- List helpers -------------------------------------------------------

    private fun refreshAppList() {
        appButtonViews.forEachIndexed { i, btn ->
            val entry = appEntries.getOrNull(i) ?: return@forEachIndexed
            btn.background = buttonBg(i == appListIndex, ctx)
        }
    }

    private fun scrollToSelectedApp() {
        val scrollView = appScrollView ?: return
        val btn = appButtonViews.getOrNull(appListIndex) ?: return
        scrollView.post {
            val top = btn.top; val bottom = btn.bottom
            val scrollY = scrollView.scrollY; val visible = scrollView.height
            if (top < scrollY) scrollView.smoothScrollTo(0, top)
            else if (bottom > scrollY + visible) scrollView.smoothScrollTo(0, bottom - visible)
        }
    }

    private fun getIndexAtY(rawY: Float): Int {
        val loc = IntArray(2)
        for (i in appButtonViews.indices) {
            appButtonViews[i].getLocationOnScreen(loc)
            if (rawY < loc[1] + appButtonViews[i].height) return i
        }
        return (appButtonViews.size - 1).coerceAtLeast(0)
    }

    private fun swapAppButtons(from: Int, to: Int) {
        appEntries.add(to, appEntries.removeAt(from))
        appButtonViews.add(to, appButtonViews.removeAt(from))
        val container = appButtonList ?: return
        container.removeAllViews()
        appButtonViews.forEach { container.addView(it) }
        refreshAppList()
        appButtonViews[to].background = buttonBg(true, ctx)
    }

    // ---- Prefs helpers ------------------------------------------------------

    private fun loadSelectedApps(): List<Pair<String, String>> {
        val raw = bridge.getStringPref(KEY_SELECTED_APPS, null)
        if (raw == null) {
            saveSelectedApps(DEFAULT_APPS)
            return DEFAULT_APPS
        }
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { entry ->
            val idx = entry.indexOf('|')
            if (idx > 0) entry.substring(0, idx) to entry.substring(idx + 1) else null
        }
    }

    private fun saveSelectedApps(apps: List<Pair<String, String>>) {
        bridge.putStringPref(KEY_SELECTED_APPS, apps.joinToString("\n") { (label, pkg) -> "$label|$pkg" })
    }
}
