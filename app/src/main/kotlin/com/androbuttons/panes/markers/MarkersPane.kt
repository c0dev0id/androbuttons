package com.androbuttons.panes.markers

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MarkersPane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "Markers"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    // ---- Constants ----------------------------------------------------------

    private companion object {
        val DEFAULT_LABELS = listOf("Good Road", "Bad Road", "Nice View", "Map Error", "Blocked")
        const val KEY_MARKER_LABELS  = "marker_labels"
        const val AUTHORITY          = "com.androbuttons.fileprovider"
        val NAV_PACKAGES = listOf(
            "com.thorkracing.dmd2launcher",
        )
        const val GPS_TIMEOUT_MS = 10_000L
        const val FRESHNESS_MS   = 30_000L
        const val GPX_MIME       = "application/gpx+xml"
    }

    // ---- State --------------------------------------------------------------

    private var focusIndex = 0
    private val markerButtons = mutableListOf<TextView>()
    private var markerLabels: List<String> = DEFAULT_LABELS
    private var inConfigureView = false

    private var paneRoot: LinearLayout? = null
    private var markerScrollView: ScrollView? = null

    private var locationManager: LocationManager? = null
    private var pendingListener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLabel: String? = null
    private var pendingButton: TextView? = null
    private var pendingOriginalLabel: String? = null

    // ---- PaneContent --------------------------------------------------------

    override fun buildView(): View {
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

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
        showMarkersView()
        return pane
    }

    override fun onResumed() {
        if (!inConfigureView) {
            focusIndex = 0
            refreshFocus()
        }
    }

    override fun onPaused() {
        cancelPendingGps()
    }

    override fun onDestroy() {
        cancelPendingGps()
    }

    override fun onUp(): Boolean {
        if (inConfigureView) return true
        if (focusIndex > 0) {
            focusIndex--
            refreshFocus()
        }
        return true
    }

    override fun onDown(): Boolean {
        if (inConfigureView) return true
        if (focusIndex < markerButtons.size - 1) {
            focusIndex++
            refreshFocus()
        }
        return true
    }

    override fun onEnter(): Boolean {
        if (inConfigureView) return true
        markerButtons.getOrNull(focusIndex)?.performClick()
        return true
    }

    // ---- Markers view -------------------------------------------------------

    private fun showMarkersView() {
        val pane = paneRoot ?: return
        pane.removeAllViews()
        inConfigureView = false

        markerLabels = loadMarkerLabels()
        focusIndex = 0
        markerButtons.clear()

        val buttonList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        markerLabels.forEachIndexed { i, label ->
            val btn = buildMarkerButton(label, isFocused = i == 0)
            markerButtons.add(btn)
            buttonList.addView(btn)
        }

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(buttonList)
        }
        markerScrollView = scrollView
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

    // ---- Configure view -----------------------------------------------------

    private fun showConfigureView() {
        val pane = paneRoot ?: return
        pane.removeAllViews()
        inConfigureView = true

        val editFields = mutableListOf<EditText>()

        val rowContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun addLabelRow(initial: String) {
            val editText = EditText(ctx).apply {
                setText(initial)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setHintTextColor(Theme.textSecondary)
                hint = "Label"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                background = null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, 8.dp(), 0)
            }
            editFields.add(editText)

            val removeBtn = TextView(ctx).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                isClickable = true
                setOnClickListener {
                    editFields.remove(editText)
                    rowContainer.removeView(this.parent as View)
                }
            }

            rowContainer.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4.dp() }
                background = buttonBg(false, ctx)
                addView(editText)
                addView(removeBtn)
            })
        }

        markerLabels.forEach { addLabelRow(it) }

        val addBtn = TextView(ctx).apply {
            text = "+ Add"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Theme.textSecondary)
            background = actionButtonBg(Theme.inactiveBg, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
            isClickable = true
            setOnClickListener { addLabelRow("") }
        }
        rowContainer.addView(addBtn)

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
                val newLabels = editFields.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
                saveMarkerLabels(newLabels)
                showMarkersView()
            }
        })
    }

    // ---- Button builder -----------------------------------------------------

    private fun buildMarkerButton(label: String, isFocused: Boolean): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = buttonBg(isFocused, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = true
            setOnClickListener { onMarkerTapped(label) }
        }
    }

    // ---- Focus management ---------------------------------------------------

    private fun refreshFocus() {
        markerButtons.forEachIndexed { i, btn ->
            btn.background = buttonBg(i == focusIndex, ctx)
        }
    }

    // ---- Marker action ------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun onMarkerTapped(label: String) {
        if (pendingLabel != null) return
        val locMgr = locationManager ?: return

        val btnIndex = markerLabels.indexOf(label)
        val btn = markerButtons.getOrNull(btnIndex) ?: return

        pendingLabel = label
        pendingButton = btn
        pendingOriginalLabel = label

        val lastKnown = try {
            locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { null }

        val now = System.currentTimeMillis()
        if (lastKnown != null && lastKnown.time > 0 && (now - lastKnown.time) < FRESHNESS_MS) {
            pendingLabel = null
            generateAndShare(label, lastKnown)
            return
        }

        startCountdown(10)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handler.removeCallbacksAndMessages(null)
                locMgr.removeUpdates(this)
                pendingListener = null
                pendingLabel = null
                generateAndShare(label, location)
            }
        }
        pendingListener = listener

        val timeoutRunnable = Runnable {
            locMgr.removeUpdates(listener)
            pendingListener = null
            pendingLabel = null
            if (lastKnown != null) {
                generateAndShare(label, lastKnown)
            } else {
                showGpsError()
            }
        }

        try {
            locMgr.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
            handler.postDelayed(timeoutRunnable, GPS_TIMEOUT_MS)
        } catch (_: Exception) {
            pendingLabel = null
            showGpsError()
        }
    }

    private fun startCountdown(secondsLeft: Int) {
        val btn = pendingButton ?: return
        btn.text = "Waiting for GPS $secondsLeft…"
        if (secondsLeft > 0) {
            handler.postDelayed({ startCountdown(secondsLeft - 1) }, 1_000L)
        }
    }

    private fun cancelPendingGps() {
        handler.removeCallbacksAndMessages(null)
        pendingListener?.let {
            try { locationManager?.removeUpdates(it) } catch (_: Exception) {}
            pendingListener = null
        }
        pendingButton?.let { btn ->
            val idx = markerLabels.indexOf(pendingOriginalLabel)
            btn.background = buttonBg(idx == focusIndex, ctx)
            btn.text = pendingOriginalLabel ?: ""
            btn.setTextColor(Color.WHITE)
        }
        pendingButton = null
        pendingOriginalLabel = null
        pendingLabel = null
    }

    // ---- GPX generation -----------------------------------------------------

    private fun generateAndShare(label: String, location: Location) {
        val gpxFile = writeGpxFile(label, location)
        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, gpxFile)
        sendGpxUri(uri)
        showSuccess()
    }

    private fun writeGpxFile(label: String, location: Location): File {
        val dateDisplay = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())
        val isoTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(location.time))

        val gpxContent = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1"
     creator="Androbuttons"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <wpt lat="${location.latitude}" lon="${location.longitude}">
    <time>$isoTime</time>
    <name>$label ($dateDisplay)</name>
    <desc>$label</desc>
  </wpt>
</gpx>"""

        val gpxDir = File(ctx.cacheDir, "gpx").apply { mkdirs() }
        return File(gpxDir, "marker_${System.currentTimeMillis()}.gpx").apply { writeText(gpxContent) }
    }

    // ---- Intent construction ------------------------------------------------

    private fun sendGpxUri(uri: Uri) {
        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, GPX_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Try system-default resolution first (respects user's "Always" choice)
        val resolved = ctx.packageManager.resolveActivity(baseIntent, 0)
        if (resolved != null) {
            try {
                ctx.startActivity(Intent(baseIntent).apply { setPackage(resolved.activityInfo.packageName) })
                return
            } catch (_: ActivityNotFoundException) { /* fall through to NAV_PACKAGES */ }
        }

        // Fallback: try known nav packages explicitly
        for (pkg in NAV_PACKAGES) {
            val intent = Intent(baseIntent).apply { setPackage(pkg) }
            if (intent.resolveActivity(ctx.packageManager) != null) {
                try {
                    ctx.startActivity(intent)
                    return
                } catch (_: ActivityNotFoundException) { /* try next */ }
            }
        }

        showGpsError()
    }

    // ---- GPS feedback -------------------------------------------------------

    private fun showSuccess() {
        val btn = pendingButton ?: return
        val originalLabel = pendingOriginalLabel ?: return
        pendingButton = null
        pendingOriginalLabel = null
        btn.text = "Marker created"
        btn.setBackgroundColor(Color.parseColor("#388E3C"))
        btn.setTextColor(Color.WHITE)
        handler.postDelayed({
            val idx = markerLabels.indexOf(originalLabel)
            btn.background = buttonBg(idx == focusIndex, ctx)
            btn.text = originalLabel
            btn.setTextColor(Color.WHITE)
        }, 2_000L)
    }

    private fun showGpsError() {
        val btn = pendingButton ?: return
        val originalLabel = pendingOriginalLabel ?: return
        pendingButton = null
        pendingOriginalLabel = null
        btn.text = "No GPS signal"
        btn.setTextColor(Color.parseColor("#F57C00"))
        handler.postDelayed({
            val idx = markerLabels.indexOf(originalLabel)
            btn.background = buttonBg(idx == focusIndex, ctx)
            btn.text = originalLabel
            btn.setTextColor(Color.WHITE)
        }, 2_000L)
    }

    // ---- Prefs helpers ------------------------------------------------------

    private fun loadMarkerLabels(): List<String> {
        val raw = bridge.getStringPref(KEY_MARKER_LABELS, null)
        if (raw == null) {
            saveMarkerLabels(DEFAULT_LABELS)
            return DEFAULT_LABELS
        }
        val loaded = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return loaded.ifEmpty { DEFAULT_LABELS }
    }

    private fun saveMarkerLabels(labels: List<String>) {
        bridge.putStringPref(KEY_MARKER_LABELS, labels.joinToString("\n"))
    }
}
