package com.androbuttons.panes.sensors

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.androbuttons.AltimeterView
import com.androbuttons.CompassView
import com.androbuttons.ForceDisplayView
import com.androbuttons.GpsInfoView
import com.androbuttons.LeanAngleView
import com.androbuttons.SpeedometerView
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.bezeledBg
import com.androbuttons.common.dpWith
import com.androbuttons.common.sunkenInstrumentBg

class SensorsPane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "Sensors"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private companion object {
        const val KEY_LEAN_CALIBRATION = "lean_cal_offset"
    }

    // View references — set during buildView(), used by SensorCoordinator
    private var compassView: CompassView? = null
    private var speedometerView: SpeedometerView? = null
    private var altimeterView: AltimeterView? = null
    private var leanAngleView: LeanAngleView? = null
    private var forceDisplayView: ForceDisplayView? = null
    private var gpsInfoView: GpsInfoView? = null

    private var coordinator: SensorCoordinator? = null

    // -------------------------------------------------------------------------
    // PaneContent
    // -------------------------------------------------------------------------

    override fun buildView(): View {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
        }

        // Wraps an instrument view in a near-black bezel container to look sunken into leather
        fun wrapSunken(view: View, cornerDp: Int = 8) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = sunkenInstrumentBg(cornerDp, ctx)
            val pad = 3.dp()
            setPadding(pad, pad, pad, pad)
            clipToOutline = true
            addView(view)
        }

        fun sectionHeader(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 9f
            setTextColor(Color.parseColor("#F57C00"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }
        }

        fun spacer() = Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8.dp()
            )
        }

        // --- Compass + G-Force (side by side) ---
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val compassCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        compassCol.addView(sectionHeader("COMPASS"))
        compassView = CompassView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        compassCol.addView(wrapSunken(compassView!!))
        topRow.addView(compassCol)

        topRow.addView(Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(4.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
        })

        val forceCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        forceCol.addView(sectionHeader("G-FORCE"))
        forceDisplayView = ForceDisplayView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        forceCol.addView(wrapSunken(forceDisplayView!!))
        topRow.addView(forceCol)

        inner.addView(topRow)
        inner.addView(spacer())

        // --- Speedometer + Altimeter (side by side) ---
        val speedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val speedCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        speedCol.addView(sectionHeader("SPEED"))
        speedometerView = SpeedometerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        speedCol.addView(wrapSunken(speedometerView!!))
        speedRow.addView(speedCol)

        speedRow.addView(Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(4.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
        })

        val altCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        altCol.addView(sectionHeader("ALTITUDE"))
        altimeterView = AltimeterView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        altCol.addView(wrapSunken(altimeterView!!))
        speedRow.addView(altCol)

        inner.addView(speedRow)
        inner.addView(spacer())

        // --- Lean Angle ---
        inner.addView(sectionHeader("LEAN"))
        leanAngleView = LeanAngleView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(wrapSunken(leanAngleView!!, 8))

        inner.addView(spacer())

        // --- GPS Info ---
        inner.addView(sectionHeader("GPS"))
        gpsInfoView = GpsInfoView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(wrapSunken(gpsInfoView!!, 4))

        inner.addView(TextView(ctx).apply {
            text = "CALIBRATE"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F57C00"))
            background = bezeledBg(Color.parseColor("#1A1A1A"), 24, ctx).also { ld ->
                // Add orange stroke on the inner layer
                (ld.getDrawable(1) as? GradientDrawable)?.setStroke(2.dp(), Color.parseColor("#F57C00"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(8.dp(), 14.dp(), 8.dp(), 14.dp())
            isClickable = true
            setOnClickListener {
                val raw = coordinator?.currentRollDeg ?: 0f
                bridge.putFloatPref(KEY_LEAN_CALIBRATION, raw)
                coordinator?.leanCalibrationOffset = raw
            }
        })

        // Start sensors immediately so data is ready whenever the user arrives
        startCoordinator()

        return ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
            addView(inner)
        }
    }

    override fun onResumed() = startCoordinator()

    override fun onPaused() {
        coordinator?.stop()
        coordinator = null
    }

    override fun onDestroy() {
        coordinator?.stop()
        coordinator = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun startCoordinator() {
        if (coordinator == null) {
            coordinator = SensorCoordinator().also { it.start() }
        }
    }

    // -------------------------------------------------------------------------
    // Inner: SensorCoordinator
    // -------------------------------------------------------------------------

    private inner class SensorCoordinator {

        private val sensorMgr  = ctx.getSystemService(Context.SENSOR_SERVICE)  as SensorManager
        private val locationMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        var leanCalibrationOffset: Float = bridge.getFloatPref(KEY_LEAN_CALIBRATION, 0f)
        var currentRollDeg: Float = 0f

        private var gpsSpeedKmh: Float  = 0f
        private var gpsBearing:  Float? = null

        private var gpsSatellites:   Int   = 0
        private var gpsAccuracyM:    Float = 0f
        private var gpsUpdateRateHz: Float = 0f
        private var lastLocationTimeMs: Long = 0L

        private var stillnessStartMs: Long = 0L
        private var lastLinearAccelMagnitude: Float = 0f

        private val AUTOLEVEL_STILL_THRESHOLD_G   = 0.07f
        private val AUTOLEVEL_STILL_DURATION_MS   = 1500L
        private val AUTOLEVEL_SPEED_THRESHOLD_KMH = 5f

        private val rotationMatrix    = FloatArray(9)
        private val orientationAngles = FloatArray(3)

        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                        val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                        val rollDeg    = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                        currentRollDeg = rollDeg

                        val heading = if (gpsSpeedKmh > 5f && gpsBearing != null) gpsBearing!! else azimuthDeg
                        compassView?.setAzimuth(heading)

                        // Auto-level: if still and slow for long enough, re-zero the lean offset
                        val now = System.currentTimeMillis()
                        val isStill = lastLinearAccelMagnitude < AUTOLEVEL_STILL_THRESHOLD_G
                        val isSlow  = gpsSpeedKmh < AUTOLEVEL_SPEED_THRESHOLD_KMH
                        if (isStill && isSlow) {
                            if (stillnessStartMs == 0L) stillnessStartMs = now
                            else if (now - stillnessStartMs >= AUTOLEVEL_STILL_DURATION_MS) {
                                leanCalibrationOffset = rollDeg
                                bridge.putFloatPref(KEY_LEAN_CALIBRATION, leanCalibrationOffset)
                                stillnessStartMs = 0L
                            }
                        } else {
                            stillnessStartMs = 0L
                        }

                        leanAngleView?.setLeanDegrees(rollDeg - leanCalibrationOffset)
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                        forceDisplayView?.setForce(ax, ay)
                        lastLinearAccelMagnitude = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        private val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                gpsSatellites = status.satelliteCount
                gpsInfoView?.update(gpsSatellites, gpsAccuracyM, gpsUpdateRateHz)
            }
        }

        private val locationListener = LocationListener { location: Location ->
            gpsSpeedKmh = location.speed * 3.6f
            gpsBearing  = if (location.hasBearing()) location.bearing else null
            speedometerView?.setSpeedKmh(gpsSpeedKmh)
            if (location.hasAltitude()) altimeterView?.setAltitudeM(location.altitude.toFloat())

            if (location.hasAccuracy()) gpsAccuracyM = location.accuracy
            val now = System.currentTimeMillis()
            if (lastLocationTimeMs > 0L) {
                val deltaMs = now - lastLocationTimeMs
                if (deltaMs > 0) gpsUpdateRateHz = 1000f / deltaMs
            }
            lastLocationTimeMs = now
            gpsInfoView?.update(gpsSatellites, gpsAccuracyM, gpsUpdateRateHz)
        }

        fun start() {
            sensorMgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensorMgr.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorMgr.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
                sensorMgr.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            try {
                locationMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener)
                locationMgr.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            } catch (_: SecurityException) { /* GPS permission denied — degrade gracefully */ }
        }

        fun stop() {
            sensorMgr.unregisterListener(sensorListener)
            try {
                locationMgr.removeUpdates(locationListener)
                locationMgr.unregisterGnssStatusCallback(gnssCallback)
            } catch (_: Exception) {}
        }
    }
}
