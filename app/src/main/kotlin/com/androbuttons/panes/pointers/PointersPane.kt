package com.androbuttons.panes.pointers

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
    }

    // ---- Data model ---------------------------------------------------------

    private data class Poi(val name: String, val lon: Double, val lat: Double)

    // ---- State --------------------------------------------------------------

    private var pois: List<Poi> = emptyList()
    private var arrowViews: List<PointerArrowView> = emptyList()
    private var paneRoot: LinearLayout? = null
    private var inConfigureView = false
    private var coordinator: PointerCoordinator? = null

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
    }

    override fun onDestroy() {
        coordinator?.stop()
        coordinator = null
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
            }
            val row = Row(nameField, coordField)
            rows.add(row)

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
            setOnClickListener { addPoiRow("", "") }
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
                val newPois = rows.mapNotNull { (nameField, coordField) ->
                    val name = nameField.text.toString().trim()
                    parsePoi(name, coordField.text.toString().trim())
                }
                savePois(newPois)
                showPointersView()
                startCoordinator()
            }
        })
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
}
