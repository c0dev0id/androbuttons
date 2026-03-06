package com.androbuttons.panes.markers

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
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith
import com.androbuttons.common.sunkenInstrumentBg
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
        val MARKER_LABELS = listOf("Good Road", "Bad Road", "Nice View", "Map Error", "Blocked")
        const val AUTHORITY      = "com.androbuttons.fileprovider"
        const val DMD2_PACKAGE   = "com.thorkracing.dmd2launcher"
        const val GPS_TIMEOUT_MS = 10_000L
        const val FRESHNESS_MS   = 30_000L
        const val GPX_MIME       = "application/gpx+xml"
    }

    // ---- State --------------------------------------------------------------

    private var focusIndex = 0
    private val markerButtons = mutableListOf<TextView>()

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

        fun wrapSunken(view: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = sunkenInstrumentBg(8, ctx)
            val pad = 3.dp()
            setPadding(pad, pad, pad, pad)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            addView(view)
        }

        markerButtons.clear()
        MARKER_LABELS.forEachIndexed { i, label ->
            val btn = buildMarkerButton(label, isFocused = i == 0)
            markerButtons.add(btn)
            pane.addView(wrapSunken(btn))
        }
        return pane
    }

    override fun onResumed() {
        focusIndex = 0
        refreshFocus()
    }

    override fun onPaused() {
        cancelPendingGps()
    }

    override fun onDestroy() {
        cancelPendingGps()
    }

    override fun onUp(): Boolean {
        if (focusIndex > 0) {
            focusIndex--
            refreshFocus()
        }
        return true
    }

    override fun onDown(): Boolean {
        if (focusIndex < markerButtons.size - 1) {
            focusIndex++
            refreshFocus()
        }
        return true
    }

    override fun onEnter(): Boolean {
        markerButtons.getOrNull(focusIndex)?.performClick()
        return true
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
            )
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

    private fun onMarkerTapped(label: String) {
        if (pendingLabel != null) return
        val locMgr = locationManager ?: return

        val btnIndex = MARKER_LABELS.indexOf(label)
        val btn = markerButtons.getOrNull(btnIndex) ?: return

        pendingLabel = label
        pendingButton = btn
        pendingOriginalLabel = label

        val lastKnown = try {
            locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) { null }

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
        } catch (_: SecurityException) {
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
            val idx = MARKER_LABELS.indexOf(pendingOriginalLabel)
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
        shareGpxUri(uri, label)
        showSuccess()
    }

    private fun writeGpxFile(label: String, location: Location): File {
        val dateForName = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
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
    <name>Marker $dateForName</name>
    <desc>$label</desc>
  </wpt>
</gpx>"""

        val gpxDir = File(ctx.cacheDir, "gpx").apply { mkdirs() }
        return File(gpxDir, "marker_${System.currentTimeMillis()}.gpx").apply { writeText(gpxContent) }
    }

    // ---- Intent construction ------------------------------------------------

    private fun shareGpxUri(uri: Uri, label: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = GPX_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Marker: $label")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(DMD2_PACKAGE)
        }

        if (intent.resolveActivity(ctx.packageManager) != null) {
            ctx.startActivity(intent)
        } else {
            val chooser = Intent.createChooser(intent.apply { setPackage(null) }, "Share GPX Marker")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        }
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
            val idx = MARKER_LABELS.indexOf(originalLabel)
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
            val idx = MARKER_LABELS.indexOf(originalLabel)
            btn.background = buttonBg(idx == focusIndex, ctx)
            btn.text = originalLabel
            btn.setTextColor(Color.WHITE)
        }, 2_000L)
    }
}
