package com.androbuttons.panes.pointers

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.androbuttons.PointerArrowView
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith
import com.androbuttons.common.roundedBg
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PointersPane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "Pointers"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    // ---- Constants ----------------------------------------------------------

    private companion object {
        const val KEY_POINTER_POIS = "pointer_pois"
        val COORD_REGEX = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,\s]+\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    }

    // ---- Data model ---------------------------------------------------------

    private data class Poi(val name: String, val lon: Double, val lat: Double)

    // ---- State --------------------------------------------------------------

    private var pois: List<Poi> = emptyList()
    private var arrowViews: List<PointerArrowView> = emptyList()
    private var paneRoot: LinearLayout? = null
    private var inConfigureView = false
    private var coordinator: PointerCoordinator? = null

    // Preserved configure-view widgets so search can navigate back without data loss
    private var configureScrollView: ScrollView? = null
    private var configureSaveBtn: View? = null

    // ---- Clipboard state ----------------------------------------------------

    private var clipMgr: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var suggBar: LinearLayout? = null
    private var suggLabel: TextView? = null

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
        showPointersView()
        return pane
    }

    override fun onResumed() {
        if (!inConfigureView) startCoordinator()
    }

    override fun onPaused() {
        coordinator?.stop()
        coordinator = null
        unregisterClipListener()
    }

    override fun onDestroy() {
        coordinator?.stop()
        coordinator = null
        unregisterClipListener()
    }

    // ---- Main view ----------------------------------------------------------

    private fun showPointersView() {
        val pane = paneRoot ?: return
        pane.removeAllViews()
        inConfigureView = false

        pois = loadPois()
        val newArrowViews = mutableListOf<PointerArrowView>()

        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val poiList = pois
        var i = 0
        while (i < poiList.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4.dp() }
            }

            // Left column
            val leftView = PointerArrowView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setLabel(poiList[i].name)
            }
            newArrowViews.add(leftView)
            row.addView(leftView)

            row.addView(Space(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(4.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
            })

            // Right column — POI or empty placeholder
            if (i + 1 < poiList.size) {
                val rightView = PointerArrowView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setLabel(poiList[i + 1].name)
                }
                newArrowViews.add(rightView)
                row.addView(rightView)
            } else {
                row.addView(Space(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }

            grid.addView(row)
            i += 2
        }

        arrowViews = newArrowViews

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(grid)
        }
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

        clipMgr = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var lastFocusedCoordField: EditText? = null

        data class Row(val nameField: EditText, val coordField: EditText)
        val rows = mutableListOf<Row>()

        val rowContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun addPoiRow(initialName: String, initialCoords: String) {
            val nameField = EditText(ctx).apply {
                setText(initialName)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setHintTextColor(Theme.textSecondary)
                hint = "Name"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                background = null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, 6.dp(), 0)
            }
            val coordField = EditText(ctx).apply {
                setText(initialCoords)
                textSize = 12f
                setTextColor(Color.WHITE)
                setHintTextColor(Theme.textSecondary)
                hint = "lon,lat"
                inputType = InputType.TYPE_CLASS_TEXT
                background = null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
                setPadding(0, 0, 6.dp(), 0)
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) lastFocusedCoordField = this
                }
            }
            val row = Row(nameField, coordField)
            rows.add(row)

            val searchBtn = TextView(ctx).apply {
                text = "🔍"
                textSize = 16f
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                isClickable = true
                setOnClickListener { showSearchView(nameField, coordField) }
            }

            // Paste button — explicit clipboard paste for overlay windows where
            // the long-press context menu is often suppressed by the system.
            val pasteBtn = TextView(ctx).apply {
                text = "⎘"   // U+2398 clipboard/helm symbol
                textSize = 16f
                setTextColor(Theme.primary)
                gravity = Gravity.CENTER
                setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                isClickable = true
                setOnClickListener {
                    val coords = extractCoordsFromClipboard() ?: return@setOnClickListener
                    coordField.setText(coords)
                    coordField.setSelection(coords.length)
                }
            }

            val removeBtn = TextView(ctx).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                isClickable = true
                setOnClickListener {
                    rows.remove(row)
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
                addView(nameField)
                addView(coordField)
                addView(searchBtn)
                addView(pasteBtn)
                addView(removeBtn)
            })
        }

        pois.forEach { addPoiRow(it.name, "${it.lon},${it.lat}") }

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
            setOnClickListener {
                // Pre-populate coord field when clipboard already holds valid coords.
                addPoiRow("", extractCoordsFromClipboard() ?: "")
            }
        }
        rowContainer.addView(addBtn)

        val sv = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rowContainer)
        }
        configureScrollView = sv
        pane.addView(sv)

        // ---- Clipboard suggestion bar ---------------------------------------
        // Appears (with faint orange tint) when clipboard holds valid coords.
        // "Apply" fills whichever coord field was most recently focused.

        val label = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Theme.textSecondary)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8.dp(), 0, 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        suggLabel = label

        val applyBtn = TextView(ctx).apply {
            text = "Apply"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.primary)
            gravity = Gravity.CENTER
            setPadding(12.dp(), 0, 12.dp(), 0)
            isClickable = true
            setOnClickListener {
                val coords = extractCoordsFromClipboard() ?: return@setOnClickListener
                val target = lastFocusedCoordField ?: rows.lastOrNull()?.coordField ?: return@setOnClickListener
                target.setText(coords)
                target.setSelection(coords.length)
            }
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dp() }
            background = roundedBg(Theme.playingRow, 6, ctx)
            addView(label)
            addView(applyBtn)
            visibility = View.GONE
        }
        suggBar = bar
        pane.addView(bar)

        // ---- Evaluate current clipboard, then register change listener ------

        fun refreshSuggBar() {
            val coords = extractCoordsFromClipboard()
            if (coords != null) {
                suggLabel?.text = "Clipboard: $coords"
                suggBar?.visibility = View.VISIBLE
            } else {
                suggBar?.visibility = View.GONE
            }
        }

        refreshSuggBar()

        val listener = ClipboardManager.OnPrimaryClipChangedListener { refreshSuggBar() }
        clipListener = listener
        clipMgr?.addPrimaryClipChangedListener(listener)

        // ---- Save button ----------------------------------------------------

        val saveBtn = TextView(ctx).apply {
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
                unregisterClipListener()
                val newPois = rows.mapNotNull { (nameField, coordField) ->
                    val name = nameField.text.toString().trim()
                    parsePoi(name, coordField.text.toString().trim())
                }
                savePois(newPois)
                showPointersView()
                startCoordinator()
            }
        }
        configureSaveBtn = saveBtn
        pane.addView(saveBtn)
    }

    // ---- Search view --------------------------------------------------------

    private fun showSearchView(nameField: EditText, coordField: EditText) {
        val pane = paneRoot ?: return
        pane.removeAllViews()

        val queryField = EditText(ctx).apply {
            hint = "Search for place or address"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Theme.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = buttonBg(false, ctx)
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }

        val resultsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val searchBtn = TextView(ctx).apply {
            text = "Search"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = actionButtonBg(Theme.primary, ctx)
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            isClickable = true
            setOnClickListener {
                val q = queryField.text.toString().trim()
                if (q.isNotEmpty()) searchNominatim(q, resultsContainer, nameField, coordField)
            }
        }

        val backBtn = TextView(ctx).apply {
            text = "← Back"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Theme.textSecondary)
            background = actionButtonBg(Theme.inactiveBg, ctx)
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            isClickable = true
            setOnClickListener { restoreConfigureView() }
        }

        pane.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(queryField)
            addView(searchBtn)
        })
        pane.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(resultsContainer)
        })
        pane.addView(backBtn)
    }

    private fun restoreConfigureView() {
        val pane = paneRoot ?: return
        val sv = configureScrollView ?: return
        val sb = configureSaveBtn ?: return
        pane.removeAllViews()
        pane.addView(sv)
        suggBar?.let { pane.addView(it) }
        pane.addView(sb)
    }

    private fun searchNominatim(
        query: String,
        resultsContainer: LinearLayout,
        nameField: EditText,
        coordField: EditText
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        resultsContainer.removeAllViews()
        resultsContainer.addView(TextView(ctx).apply {
            text = "Searching…"
            setTextColor(Theme.textSecondary)
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        })

        Thread {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val conn = java.net.URL(
                    "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5"
                ).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Androbuttons/1.0")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val results = org.json.JSONArray(json)
                handler.post {
                    resultsContainer.removeAllViews()
                    if (results.length() == 0) {
                        resultsContainer.addView(TextView(ctx).apply {
                            text = "No results found"
                            setTextColor(Theme.textSecondary)
                            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
                        })
                    } else {
                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val displayName = item.getString("display_name")
                            val lat = item.getString("lat").toDouble()
                            val lon = item.getString("lon").toDouble()
                            val shortName = displayName.substringBefore(",").trim()

                            resultsContainer.addView(TextView(ctx).apply {
                                text = displayName
                                textSize = 12f
                                setTextColor(Color.WHITE)
                                setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
                                background = buttonBg(false, ctx)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = 4.dp() }
                                isClickable = true
                                setOnClickListener {
                                    nameField.setText(shortName)
                                    coordField.setText("%.6f,%.6f".format(lon, lat))
                                    restoreConfigureView()
                                }
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    resultsContainer.removeAllViews()
                    resultsContainer.addView(TextView(ctx).apply {
                        text = "Search failed: check connection"
                        setTextColor(Color.parseColor("#F57C00"))
                        setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
                    })
                }
            }
        }.start()
    }

    // ---- Coordinator lifecycle -----------------------------------------------

    private fun startCoordinator() {
        if (coordinator == null && arrowViews.isNotEmpty()) {
            coordinator = PointerCoordinator().also { it.start() }
        }
    }

    // ---- Inner: PointerCoordinator ------------------------------------------

    private inner class PointerCoordinator {

        private val sensorMgr   = ctx.getSystemService(Context.SENSOR_SERVICE)  as SensorManager
        private val locationMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        private var currentHeadingDeg: Float = 0f
        private var currentLat: Double? = null
        private var currentLon: Double? = null
        private var gpsSpeedKmh: Float  = 0f
        private var gpsBearing: Float?  = null

        private val rotationMatrix    = FloatArray(9)
        private val orientationAngles = FloatArray(3)

        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    currentHeadingDeg = if (gpsSpeedKmh > 5f && gpsBearing != null) gpsBearing!! else azimuth
                    updateArrows()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        private val locationListener = LocationListener { location: Location ->
            gpsSpeedKmh = location.speed * 3.6f
            gpsBearing  = if (location.hasBearing()) location.bearing else null
            currentLat  = location.latitude
            currentLon  = location.longitude
            updateArrows()
        }

        private fun updateArrows() {
            val lat = currentLat ?: return
            val lon = currentLon ?: return
            arrowViews.zip(pois).forEach { (view, poi) ->
                val bearing  = bearingTo(lat, lon, poi.lat, poi.lon)
                val dist     = distanceTo(lat, lon, poi.lat, poi.lon)
                val relative = normalizeBearing(bearing - currentHeadingDeg)
                view.setRelativeBearing(relative)
                view.setDistance(dist)
            }
        }

        fun start() {
            sensorMgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensorMgr.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            try {
                locationMgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500L, 0f, locationListener
                )
            } catch (_: SecurityException) {}
        }

        fun stop() {
            sensorMgr.unregisterListener(sensorListener)
            try { locationMgr.removeUpdates(locationListener) } catch (_: Exception) {}
        }
    }

    // ---- Geo math -----------------------------------------------------------

    private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2r)
        val x = cos(lat1r) * sin(lat2r) - sin(lat1r) * cos(lat2r) * cos(dLon)
        return Math.toDegrees(atan2(y, x)).toFloat()
    }

    private fun distanceTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R    = 6371.0
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) + cos(lat1r) * cos(lat2r) * sin(dLon / 2).pow(2)
        return (R * 2 * atan2(sqrt(a), sqrt(1 - a))).toFloat()
    }

    private fun normalizeBearing(b: Float): Float = ((b + 540f) % 360f) - 180f

    // ---- Prefs helpers ------------------------------------------------------

    private fun loadPois(): List<Poi> {
        val raw = bridge.getStringPref(KEY_POINTER_POIS, null) ?: return emptyList()
        return raw.lines().mapNotNull { line ->
            val idx = line.indexOf('|')
            if (idx < 1) return@mapNotNull null
            val name   = line.substring(0, idx).trim()
            val coords = line.substring(idx + 1).trim()
            parsePoi(name, coords)
        }
    }

    private fun savePois(list: List<Poi>) {
        bridge.putStringPref(KEY_POINTER_POIS, list.joinToString("\n") { "${it.name}|${it.lon},${it.lat}" })
    }

    private fun parsePoi(name: String, coords: String): Poi? {
        if (name.isEmpty() || coords.isEmpty()) return null
        val parts = coords.split(",")
        if (parts.size != 2) return null
        val lon = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        return Poi(name, lon, lat)
    }

    // ---- Clipboard helpers --------------------------------------------------

    /**
     * Reads the primary clip and returns a normalised "num1,num2" string if the
     * clipboard text matches the coordinate pattern, or null otherwise.
     * Accepts comma- or space-separated decimal pairs, with optional surrounding
     * whitespace and negative values (e.g. "13.405,52.52", "13.405 52.52",
     * "-3.7, 40.4").
     */
    private fun extractCoordsFromClipboard(): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val raw = clip.getItemAt(0).coerceToText(ctx).toString()
        val match = COORD_REGEX.matchEntire(raw.trim()) ?: return null
        val (a, b) = match.destructured
        return "$a,$b"
    }

    /** Removes the clipboard listener and clears suggestion-bar view references. */
    private fun unregisterClipListener() {
        clipListener?.let { clipMgr?.removePrimaryClipChangedListener(it) }
        clipListener = null
        suggBar = null
        suggLabel = null
    }
}
