package com.androbuttons

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.TypedValue
import android.view.GestureDetector
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri

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
        // Secondary (non-configurable) gamepad mappings
        private const val SECONDARY_KEY_ENTER = KeyEvent.KEYCODE_BUTTON_Y
        private const val SECONDARY_KEY_CANCEL = KeyEvent.KEYCODE_BUTTON_A
        private const val TARGET_PLAYER_PKG = "de.codevoid.androsnd"
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_LEAN_CALIBRATION = "lean_cal_offset"
        private const val KEY_PLAYLIST_CACHE       = "playlist_cache"
        private const val KEY_PLAYLIST_FINGERPRINT = "playlist_fingerprint"

        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // Launcher pane reference for dynamic rebuild
    private var launcherPane: LinearLayout? = null

    // Pane state
    private var currentPane = 0
    private val paneCount = 3
    private val paneNames = arrayOf("Music", "Apps", "Sensors")

    // Media player state
    private var isPlaying = false

    // Music pane state
    private enum class MusicFocus { BUTTON, LIST, LIST_ENTRY }
    private var musicFocus = MusicFocus.BUTTON
    private var musicListIndex = 0
    private var currentlyPlayingMediaId: String? = null
    private val trackList = mutableListOf<TrackItem>()
    private var mediaController: MediaControllerCompat? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private val seekHandler = Handler(Looper.getMainLooper())
    private var musicScrollView: ScrollView? = null
    private var trackListContainer: LinearLayout? = null
    private val trackRowViews = mutableListOf<LinearLayout>()
    private var seekBarFill: View? = null
    private var coverArtView: ImageView? = null
    private var timeElapsed: TextView? = null
    private var timeRemaining: TextView? = null
    private var playPauseButton: LinearLayout? = null
    private var playPauseIcon: ImageView? = null
    private var playPauseLabel: TextView? = null
    private var playlistContainerView: LinearLayout? = null

    // Apps pane state
    private data class AppEntry(val label: String, val packageName: String, val isInstalled: Boolean)
    private val appEntries = mutableListOf<AppEntry>()
    private var appListIndex = 0
    private var appScrollView: ScrollView? = null
    private val appButtonViews = mutableListOf<LinearLayout>()
    private var appButtonList: LinearLayout? = null
    private var sortingMode = false
    private var sortDragIndex = -1

    // Sensors pane state
    private var compassView: CompassView? = null
    private var speedometerView: SpeedometerView? = null
    private var leanAngleView: LeanAngleView? = null
    private var forceDisplayView: ForceDisplayView? = null
    private var sensorCoordinator: SensorCoordinator? = null

    // Lazy pane containers — populated after the overlay is visible
    private var pane0Container: FrameLayout? = null
    private var pane1Container: FrameLayout? = null
    private var pane2Container: FrameLayout? = null

    // Stored view references for direct updates (no full rebuild)
    private lateinit var viewFlipper: ViewFlipper
    private var titleArrowLeft: TextView? = null
    private var titleText: TextView? = null
    private var titleArrowRight: TextView? = null
    private var titleLeftZone: LinearLayout? = null
    private var titleRightZone: LinearLayout? = null

    // Color palette
    private val primaryColor    = Color.parseColor("#F57C00")   // orange
    private val surfaceColor    = Color.parseColor("#FF2B2B2B") // opaque dark gray
    private val headerColor     = Color.parseColor("#FF3D3D3D") // slightly lighter, for header bar
    private val playerAreaColor = Color.parseColor("#FF1E1E1E") // darker, for player card
    private val secondaryText   = Color.parseColor("#B0B0B0")
    private val tertiaryText    = Color.parseColor("#808080")
    private val inactiveBg      = Color.parseColor("#444444")
    private val seekTrackColor  = Color.parseColor("#555555")
    private val playingRow      = Color.parseColor("#28F57C00") // ~16% orange tint for currently playing

    private val statusBarHeight: Int
        get() {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

    private val navBarHeight: Int
        get() {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

    private val visibleStatusBarHeight: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics
                .windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        } else {
            statusBarHeight
        }

    private val visibleNavBarHeight: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics
                .windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        } else {
            navBarHeight
        }

    private val overlayWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.25f).toInt()

    private val overlayHeight: Int
        get() {
            val screenH = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.height()
            } else {
                resources.displayMetrics.heightPixels
            }
            return screenH - visibleStatusBarHeight - visibleNavBarHeight
        }

    // --- Data ---

    private data class TrackItem(
        val mediaId: String,
        val title: String,
        val artist: String,
        val duration: Long,  // ms
        val art: android.graphics.Bitmap? = null,
        val artUri: Uri? = null
    )

    // --- Media callbacks ---

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onSessionReady() {
            // On API 26+, MediaControllerCompat performs an async handshake with the
            // framework session after construction. getMetadata() and getPlaybackState()
            // return null until this callback fires — so do all initial state sync here.
            val metadata = mediaController?.metadata
            currentlyPlayingMediaId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
            updateNowPlaying(metadata)
            val state = mediaController?.playbackState
            val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
            isPlaying = playing
            refreshPlayButton()
            if (playing) {
                seekHandler.removeCallbacks(seekUpdater)
                seekHandler.post(seekUpdater)
            }
        }
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            val newId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
            if (newId != currentlyPlayingMediaId) {
                currentlyPlayingMediaId = newId
                refreshTrackList()
                scrollToPlaying()
            }
            updateNowPlaying(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
            isPlaying = playing
            refreshPlayButton()
            if (playing) {
                seekHandler.removeCallbacks(seekUpdater)
                seekHandler.post(seekUpdater)
            } else {
                seekHandler.removeCallbacks(seekUpdater)
                updateSeekBar(state?.position ?: 0L,
                    mediaController?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L)
            }
        }
    }

    private val browserConnectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val browser = mediaBrowser ?: return
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = MediaControllerCompat(this@OverlayService, browser.sessionToken)
            mediaController!!.registerCallback(mediaControllerCallback, Handler(Looper.getMainLooper()))
            browser.subscribe(browser.root, browserSubscriptionCallback)
        }
        override fun onConnectionFailed() {
            android.util.Log.w("androbuttons", "MediaBrowser connection failed: ${mediaBrowser?.serviceComponent}")
        }
        override fun onConnectionSuspended() {
            android.util.Log.w("androbuttons", "MediaBrowser connection suspended")
            mediaBrowser?.disconnect()
            mediaBrowser = null
        }
    }

    private var pendingFolderSubscriptions = 0

    private val browserSubscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: List<MediaBrowserCompat.MediaItem>
        ) {
            val playable  = children.filter { it.isPlayable }
            val browsable = children.filter { it.isBrowsable }

            if (browsable.isNotEmpty() && playable.isEmpty()) {
                // Root returned folders — clear list once, then recurse into each folder
                trackList.clear()
                pendingFolderSubscriptions = browsable.size
                browsable.forEach { folder ->
                    mediaBrowser?.subscribe(folder.mediaId ?: return@forEach, this)
                }
            } else {
                // Leaf level — append playable songs
                if (parentId == (mediaBrowser?.root ?: "")) {
                    trackList.clear()
                }
                playable.forEach { item ->
                    val desc = item.description
                    trackList.add(TrackItem(
                        mediaId  = item.mediaId ?: "",
                        title    = desc.title?.toString() ?: "Unknown",
                        artist   = desc.subtitle?.toString() ?: "",
                        duration = desc.extras?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L,
                        art      = desc.iconBitmap,
                        artUri   = desc.iconUri
                    ))
                }
                if (parentId != (mediaBrowser?.root ?: "")) {
                    pendingFolderSubscriptions--
                }
                if (pendingFolderSubscriptions <= 0) {
                    musicListIndex = 0
                    val newFingerprint = trackList.joinToString(",") { it.mediaId }
                    val cachedFingerprint = prefs.getString(KEY_PLAYLIST_FINGERPRINT, "")
                    if (newFingerprint != cachedFingerprint) {
                        savePlaylistCache()
                        rebuildTrackList()
                    }
                    // else: cache is still fresh, skip the rebuild
                }
            }
        }
    }

    private val seekUpdater = object : Runnable {
        override fun run() {
            val state = mediaController?.playbackState ?: return
            val pos = if (state.state == PlaybackStateCompat.STATE_PLAYING) {
                state.position +
                    ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                        state.playbackSpeed).toLong()
            } else {
                state.position
            }
            val dur = mediaController?.metadata
                ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
            updateSeekBar(pos, dur)
            seekHandler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView != null) {
            hideOverlay()
        } else {
            showOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        seekHandler.removeCallbacks(seekUpdater)
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaBrowser?.disconnect()
        stopSensorCoordinator()
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
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = 20.dp()
            y = visibleStatusBarHeight
        }
        windowParams = params
        view.translationX = overlayWidth.toFloat()
        windowManager.addView(view, params)
        overlayView = view
        view.requestFocus()
        view.animate()
            .translationX(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
        seekHandler.post {
            buildPaneIfNeeded(0)
            connectMedia()
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    // --- Media connection ---

    private fun savePlaylistCache() {
        val arr = org.json.JSONArray()
        trackList.forEach { t ->
            arr.put(org.json.JSONObject().apply {
                put("mediaId",  t.mediaId)
                put("title",    t.title)
                put("artist",   t.artist)
                put("duration", t.duration)
                put("artUri",   t.artUri?.toString() ?: "")
            })
        }
        val fingerprint = trackList.joinToString(",") { it.mediaId }
        prefs.edit()
            .putString(KEY_PLAYLIST_CACHE, arr.toString())
            .putString(KEY_PLAYLIST_FINGERPRINT, fingerprint)
            .apply()
    }

    private fun loadPlaylistCache(): Boolean {
        val json = prefs.getString(KEY_PLAYLIST_CACHE, null) ?: return false
        return try {
            val arr = org.json.JSONArray(json)
            trackList.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uriStr = o.getString("artUri")
                trackList.add(TrackItem(
                    mediaId  = o.getString("mediaId"),
                    title    = o.getString("title"),
                    artist   = o.getString("artist"),
                    duration = o.getLong("duration"),
                    artUri   = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null
                ))
            }
            trackList.isNotEmpty()
        } catch (_: Exception) { false }
    }

    private fun connectMedia() {
        val component = findMediaBrowserComponent(TARGET_PLAYER_PKG) ?: return
        // Show cached playlist immediately while the live connection loads
        if (loadPlaylistCache()) {
            rebuildTrackList()
        }
        mediaBrowser?.disconnect()
        mediaBrowser = MediaBrowserCompat(this, component, browserConnectionCallback, null)
        mediaBrowser!!.connect()
    }

    private fun findMediaBrowserComponent(pkg: String): ComponentName? {
        for (action in listOf(
            "android.media.browse.MediaBrowserService",
            "android.support.v4.media.browse.MediaBrowserService"
        )) {
            val info = packageManager.queryIntentServices(Intent(action), 0)
                .firstOrNull { it.serviceInfo.packageName == pkg }
            if (info != null) return ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
        }
        return null
    }

    // --- UI Building ---

    private fun buildRootView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // No padding — header bar fills edge-to-edge; content padding applied inside sections
            background = createRoundedBackground(surfaceColor, 16)
            elevation = 8.dp().toFloat()
            clipToOutline = true
        }

        container.addView(buildTitleBar())
        container.addView(buildFlipperView())

        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(keyCode) else false
        }
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { hideOverlay(); true }
            else false
        }

        return container
    }

    private fun buildTitleBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleArrowLeft = TextView(this).apply {
            text = "◀"
            textSize = 13f
            setTextColor(if (currentPane > 0) primaryColor else inactiveBg)
        }

        titleText = TextView(this).apply {
            text = paneNames[currentPane]
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        titleArrowRight = TextView(this).apply {
            text = "▶"
            textSize = 13f
            setTextColor(if (currentPane < paneCount - 1) primaryColor else inactiveBg)
        }

        titleLeftZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1f }
            alpha = if (currentPane > 0) 1f else 0.4f
            addView(titleArrowLeft)
            setOnClickListener {
                if (currentPane > 0) {
                    buildPaneIfNeeded(currentPane - 1)
                    viewFlipper.inAnimation  = slideIn(fromRight = false)
                    viewFlipper.outAnimation = slideOut(toLeft = false)
                    currentPane--
                    viewFlipper.showPrevious()
                    refreshTitleBar()
                }
            }
        }

        val centerZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 12.dp())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { weight = 2f }
            addView(titleText)
        }

        titleRightZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1f }
            alpha = if (currentPane < paneCount - 1) 1f else 0.4f
            addView(titleArrowRight)
            setOnClickListener {
                if (currentPane < paneCount - 1) {
                    buildPaneIfNeeded(currentPane + 1)
                    viewFlipper.inAnimation  = slideIn(fromRight = true)
                    viewFlipper.outAnimation = slideOut(toLeft = true)
                    currentPane++
                    viewFlipper.showNext()
                    refreshTitleBar()
                }
            }
        }

        bar.addView(titleLeftZone)
        bar.addView(centerZone)
        bar.addView(titleRightZone)

        return bar
    }

    private fun refreshTitleBar() {
        titleText?.text = paneNames[currentPane]
        val leftActive = currentPane > 0
        val rightActive = currentPane < paneCount - 1
        titleArrowLeft?.setTextColor(if (leftActive) primaryColor else inactiveBg)
        titleArrowRight?.setTextColor(if (rightActive) primaryColor else inactiveBg)
        titleLeftZone?.alpha = if (leftActive) 1f else 0.4f
        titleRightZone?.alpha = if (rightActive) 1f else 0.4f
    }

    private fun buildFlipperView(): ViewFlipper {
        viewFlipper = ViewFlipper(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        pane0Container = FrameLayout(this).also { viewFlipper.addView(it) }
        pane1Container = FrameLayout(this).also { viewFlipper.addView(it) }
        pane2Container = FrameLayout(this).also { viewFlipper.addView(it) }
        viewFlipper.displayedChild = currentPane
        return viewFlipper
    }

    private fun buildPaneIfNeeded(index: Int) {
        val container = when (index) {
            0 -> pane0Container
            1 -> pane1Container
            2 -> pane2Container
            else -> null
        } ?: return
        if (container.childCount > 0) return
        when (index) {
            0 -> container.addView(buildMusicPane())
            1 -> container.addView(buildLauncherPane())
            2 -> container.addView(buildSensorsPane())
        }
    }

    private fun buildMusicPane(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // --- Player card ---
        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBackground(playerAreaColor, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f; bottomMargin = 10.dp() }
            clipToOutline = true
        }

        // Full-width cover art
        coverArtView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(inactiveBg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        playerCard.addView(coverArtView)

        // Seekbar row: elapsed — track — remaining
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        timeElapsed = TextView(this).apply {
            text = "0:00"
            textSize = 10f
            setTextColor(tertiaryText)
        }
        val seekTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, 4.dp()).apply {
                weight = 1f
                marginStart = 6.dp()
                marginEnd = 6.dp()
            }
            background = createRoundedBackground(seekTrackColor, 2)
        }
        seekBarFill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                weight = 0f
            }
            background = createRoundedBackground(primaryColor, 2)
        }
        seekTrack.addView(seekBarFill)
        seekTrack.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                weight = 1f
            }
        })
        timeRemaining = TextView(this).apply {
            text = "0:00"
            textSize = 10f
            setTextColor(tertiaryText)
        }
        seekRow.addView(timeElapsed)
        seekRow.addView(seekTrack)
        seekRow.addView(timeRemaining)
        playerCard.addView(seekRow)

        pane.addView(playerCard)

        // --- Standalone play/pause button ---
        val playIcon = ImageView(this).apply {
            val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            setImageDrawable(ContextCompat.getDrawable(this@OverlayService, iconRes))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val size = 24.dp()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dp() }
        }
        playPauseIcon = playIcon

        val playLabel = TextView(this).apply {
            text = if (isPlaying) "Pause" else "Play"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        playPauseLabel = playLabel

        playPauseButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            background = playButtonBackground(musicFocus == MusicFocus.BUTTON)
            isClickable = true
            addView(playIcon)
            addView(playLabel)
            setOnClickListener {
                if (isPlaying) mediaController?.transportControls?.pause()
                else mediaController?.transportControls?.play()
            }
        }
        pane.addView(playPauseButton)

        // --- Playlist section ---
        playlistContainerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 3f }
        }
        val playlistContainer = playlistContainerView!!

        playlistContainer.addView(TextView(this).apply {
            text = "PLAYLIST"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(tertiaryText)
            setPadding(2.dp(), 0, 2.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // Track list in a ScrollView
        musicScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
            isVerticalScrollBarEnabled = false
        }
        trackListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        musicScrollView!!.addView(trackListContainer)
        playlistContainer.addView(musicScrollView)

        pane.addView(playlistContainerView)

        // Placeholder when no tracks loaded
        trackListContainer!!.addView(TextView(this).apply {
            text = "No tracks"
            textSize = 12f
            setTextColor(tertiaryText)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp() }
        })

        return pane
    }

    private val defaultApps = listOf(
        "Voice Note" to "com.voicenotes.main",
        "Ridelink" to "otp.systems.ridelink",
        "Cardo Connect" to "com.cardo.smartset",
        "Sena +Mesh" to "com.sena.plusmesh",
        "Telegram" to "org.telegram.messenger",
        "WhatsApp" to "com.whatsapp",
        "Discord" to "com.discord",
        "Blitzer.de" to "de.blitzer.plus",
        "MeteoBlue" to "com.meteoblue.droid",
        "PACE Drive" to "car.pace.drive",
        "ryd" to "com.thinxnet.native_tanktaler_android",
        "Google Drive" to "com.google.android.apps.docs",
        "Google Photos" to "com.google.android.apps.photos",
        "Google Maps" to "com.google.android.apps.maps",
        "DMD2" to "com.thorkracing.dmd2launcher"
    )

    private fun loadSelectedApps(): List<Pair<String, String>> {
        if (!prefs.contains(KEY_SELECTED_APPS)) {
            saveSelectedApps(defaultApps)
            return defaultApps
        }
        val raw = prefs.getString(KEY_SELECTED_APPS, "") ?: ""
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { entry ->
            val idx = entry.indexOf('|')
            if (idx > 0) entry.substring(0, idx) to entry.substring(idx + 1) else null
        }
    }

    private fun saveSelectedApps(apps: List<Pair<String, String>>) {
        val encoded = apps.joinToString("\n") { (label, pkg) -> "$label|$pkg" }
        prefs.edit().putString(KEY_SELECTED_APPS, encoded).apply()
    }

    private fun buildLauncherPane(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(makePaneSwipeListener())
        }
        launcherPane = pane

        val configureBtn = TextView(this).apply {
            text = "Configure"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(secondaryText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(inactiveBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            setOnClickListener { showConfigureView() }
        }
        pane.addView(configureBtn)
        return pane
    }

    private fun showAppsView() {
        val pane = launcherPane ?: return
        pane.removeAllViews()

        appEntries.clear()
        appEntries.addAll(loadSelectedApps().map { (label, pkg) ->
            AppEntry(label, pkg, true)
        })
        appListIndex = 0
        appButtonViews.clear()

        val buttonList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        appButtonList = buttonList
        appEntries.forEachIndexed { i, entry ->
            val btn = buildAppButton(entry, i == 0, i)
            appButtonViews.add(btn)
            buttonList.addView(btn)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(buttonList)
        }
        appScrollView = scrollView

        val configureBtn = TextView(this).apply {
            text = "Configure"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(secondaryText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(inactiveBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            setOnClickListener { showConfigureView() }
        }

        pane.addView(scrollView)
        pane.addView(configureBtn)
    }

    private fun showConfigureView() {
        val pane = launcherPane ?: return
        pane.removeAllViews()

        val selectedPkgs = loadSelectedApps().map { it.second }.toSet()

        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installedApps = packageManager.queryIntentActivities(launchIntent, 0)
            .map { ri ->
                ri.activityInfo.applicationInfo.packageName to
                        ri.loadLabel(packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedWith(compareByDescending<Pair<String, String>> { it.first in selectedPkgs }
                .thenBy { it.second })

        // Track checked state per package
        val checkedState = mutableMapOf<String, Boolean>()
        installedApps.forEach { (pkg, _) -> checkedState[pkg] = pkg in selectedPkgs }

        val rowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun makeCheckbox(checked: Boolean): View {
            return View(this).apply {
                val size = 24.dp()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = 12.dp()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4.dp().toFloat()
                    if (checked) {
                        setColor(primaryColor)
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(2.dp(), secondaryText)
                    }
                }
            }
        }

        installedApps.forEach { (pkg, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 2.dp() }

                val checkbox = makeCheckbox(checkedState[pkg] == true)
                addView(checkbox)

                addView(TextView(this@OverlayService).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })

                setOnClickListener {
                    val nowChecked = !(checkedState[pkg] ?: false)
                    checkedState[pkg] = nowChecked
                    checkbox.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 4.dp().toFloat()
                        if (nowChecked) {
                            setColor(primaryColor)
                        } else {
                            setColor(Color.TRANSPARENT)
                            setStroke(2.dp(), secondaryText)
                        }
                    }
                }
            }
            rowContainer.addView(row)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(rowContainer)
        }

        val saveBtn = TextView(this).apply {
            text = "Save"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(primaryColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            setOnClickListener {
                val selected = installedApps
                    .filter { (pkg, _) -> checkedState[pkg] == true }
                    .map { (pkg, label) -> label to pkg }
                saveSelectedApps(selected)
                showAppsView()
            }
        }

        pane.addView(scrollView)
        pane.addView(saveBtn)
    }

    // -------------------------------------------------------------------------
    // Sensors pane
    // -------------------------------------------------------------------------

    private fun buildSensorsPane(): ScrollView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
        }

        fun sectionHeader(title: String): TextView = TextView(this).apply {
            text = title
            textSize = 10f
            setTextColor(tertiaryText)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }
        }

        fun spacer(): Space = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8.dp()
            )
        }

        // --- Compass + G-Force (side by side) ---
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val compassCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        compassCol.addView(sectionHeader("COMPASS"))
        compassView = CompassView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        compassCol.addView(compassView)
        topRow.addView(compassCol)

        topRow.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(4.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
        })

        val forceCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        forceCol.addView(sectionHeader("G-FORCE"))
        forceDisplayView = ForceDisplayView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        forceCol.addView(forceDisplayView)
        topRow.addView(forceCol)

        inner.addView(topRow)
        inner.addView(spacer())

        // --- Speedometer ---
        inner.addView(sectionHeader("SPEED"))
        speedometerView = SpeedometerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(speedometerView)
        inner.addView(spacer())

        // --- Lean Angle ---
        inner.addView(sectionHeader("LEAN"))
        leanAngleView = LeanAngleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inner.addView(leanAngleView)

        val calibrateBtn = TextView(this).apply {
            text = "Calibrate"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(secondaryText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(inactiveBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp() }
            setPadding(8.dp(), 14.dp(), 8.dp(), 14.dp())
            setOnClickListener {
                val raw = sensorCoordinator?.currentRollDeg ?: 0f
                prefs.edit().putFloat(KEY_LEAN_CALIBRATION, raw).apply()
                sensorCoordinator?.leanCalibrationOffset = raw
            }
        }
        inner.addView(calibrateBtn)

        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(makePaneSwipeListener())
            addView(inner)
        }
    }

    // -------------------------------------------------------------------------
    // Sensor coordinator — starts/stops with the Sensors pane
    // -------------------------------------------------------------------------

    private inner class SensorCoordinator {
        private val sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
        private val locationMgr = getSystemService(LOCATION_SERVICE) as LocationManager

        var leanCalibrationOffset: Float = prefs.getFloat(KEY_LEAN_CALIBRATION, 0f)
        var currentRollDeg: Float = 0f

        private var gpsSpeedKmh: Float = 0f
        private var gpsBearing: Float? = null

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
                        leanAngleView?.setLeanDegrees(rollDeg - leanCalibrationOffset)
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        forceDisplayView?.setForce(event.values[0], event.values[1])
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        private val locationListener = LocationListener { location: Location ->
            gpsSpeedKmh = location.speed * 3.6f
            gpsBearing  = if (location.hasBearing()) location.bearing else null
            speedometerView?.setSpeedKmh(gpsSpeedKmh)
        }

        fun start() {
            sensorMgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensorMgr.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorMgr.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
                sensorMgr.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            try {
                locationMgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500L, 0f, locationListener
                )
            } catch (_: SecurityException) { /* GPS permission denied — degrade gracefully */ }
        }

        fun stop() {
            sensorMgr.unregisterListener(sensorListener)
            try { locationMgr.removeUpdates(locationListener) } catch (_: Exception) {}
        }
    }

    private fun startSensorCoordinator() {
        if (sensorCoordinator == null) {
            sensorCoordinator = SensorCoordinator()
            sensorCoordinator!!.start()
        }
    }

    private fun stopSensorCoordinator() {
        sensorCoordinator?.stop()
        sensorCoordinator = null
    }

    private fun buildAppButton(entry: AppEntry, isFocused: Boolean, index: Int): LinearLayout {
        val icon = try { packageManager.getApplicationIcon(entry.packageName) } catch (e: Exception) { null }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            background = appButtonBackground(isFocused, entry.isInstalled)
            isClickable = true
            val gestureDetector = GestureDetector(this@OverlayService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        if (!sortingMode && entry.isInstalled) {
                            val intent = packageManager.getLaunchIntentForPackage(entry.packageName)
                                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (intent != null) { startActivity(intent); hideOverlay() }
                        }
                        return true
                    }
                    override fun onLongPress(e: MotionEvent) {
                        sortingMode = true
                        sortDragIndex = index
                        this@apply.background = appButtonBackground(true, entry.isInstalled)
                        this@apply.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            )
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.drawableHotspotChanged(event.x, event.y)
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                val handled = gestureDetector.onTouchEvent(event)
                if (sortingMode && sortDragIndex == index) {
                    when (event.action) {
                        MotionEvent.ACTION_MOVE -> {
                            val newIdx = getIndexAtY(event.rawY)
                            if (newIdx != sortDragIndex) {
                                swapAppButtons(sortDragIndex, newIdx)
                                sortDragIndex = newIdx
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            sortingMode = false
                            sortDragIndex = -1
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            saveSelectedApps(appEntries.map { it.label to it.packageName })
                            refreshAppList()
                        }
                    }
                    true
                } else {
                    handled
                }
            }

            if (icon != null) {
                addView(ImageView(this@OverlayService).apply {
                    setImageDrawable(icon)
                    val size = 36.dp()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dp() }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }

            addView(TextView(this@OverlayService).apply {
                text = entry.label
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (entry.isInstalled) Color.WHITE else secondaryText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
            })
        }
    }

    private fun buildEmptyPane(label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(TextView(this@OverlayService).apply {
                text = label
                textSize = 14f
                setTextColor(tertiaryText)
                gravity = Gravity.CENTER
                isClickable = true
                setOnTouchListener(makePaneSwipeListener())
            })
        }
    }

    // --- Music pane updates ---

    private fun rebuildTrackList() {
        val container = trackListContainer ?: return
        container.removeAllViews()
        trackRowViews.clear()

        if (trackList.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No tracks"
                textSize = 12f
                setTextColor(tertiaryText)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16.dp() }
            })
            return
        }

        trackList.forEachIndexed { index, track ->
            val row = buildTrackRow(track, musicFocus == MusicFocus.LIST_ENTRY && index == musicListIndex, track.mediaId == currentlyPlayingMediaId, index)
            trackRowViews.add(row)
            container.addView(row)
        }
        scrollToPlaying()
    }

    private fun trackRowBackground(isFocused: Boolean, isPlaying: Boolean): GradientDrawable? {
        if (!isFocused && !isPlaying) return null
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6.dp().toFloat()
            setColor(if (isPlaying) playingRow else Color.TRANSPARENT)
            if (isFocused || isPlaying) setStroke(2.dp(), primaryColor)
        }
    }

    private fun buildTrackRow(track: TrackItem, isFocused: Boolean, isPlaying: Boolean, index: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            background = trackRowBackground(isFocused, isPlaying)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2.dp() }
            isClickable = true
            setOnClickListener {
                musicListIndex = index
                mediaController?.transportControls?.playFromMediaId(track.mediaId, null)
                refreshTrackList()
            }

            // Cover art thumbnail
            val artView = ImageView(this@OverlayService).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val size = 36.dp()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 8.dp() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4.dp().toFloat()
                    setColor(Color.argb(60, 255, 255, 255))
                }
                if (track.art != null) {
                    setImageBitmap(track.art)
                } else if (track.artUri != null) {
                    val uri = track.artUri
                    val view = this
                    Thread {
                        try {
                            val bmp = contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it)
                            }
                            if (bmp != null) seekHandler.post { view.setImageBitmap(bmp) }
                        } catch (_: Exception) {}
                    }.start()
                }
            }
            addView(artView)

            // Title + artist column
            val metaCol = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
            }
            metaCol.addView(TextView(this@OverlayService).apply {
                text = track.title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (track.artist.isNotEmpty()) {
                metaCol.addView(TextView(this@OverlayService).apply {
                    text = track.artist
                    textSize = 10f
                    setTextColor(secondaryText)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addView(metaCol)

            // Duration
            if (track.duration > 0L) {
                addView(TextView(this@OverlayService).apply {
                    text = formatDuration(track.duration)
                    textSize = 10f
                    setTextColor(tertiaryText)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 4.dp() }
                })
            }
        }
    }

    private fun refreshTrackList() {
        trackRowViews.forEachIndexed { i, row ->
            val focused = musicFocus == MusicFocus.LIST_ENTRY && i == musicListIndex
            val playing = trackList.getOrNull(i)?.mediaId == currentlyPlayingMediaId
            row.background = trackRowBackground(focused, playing)
        }
    }

    private fun scrollToSelected() {
        val scrollView = musicScrollView ?: return
        val row = trackRowViews.getOrNull(musicListIndex) ?: return
        scrollView.post {
            val rowTop = row.top
            val rowBottom = row.bottom
            val scrollY = scrollView.scrollY
            val visibleHeight = scrollView.height
            if (rowTop < scrollY) {
                scrollView.smoothScrollTo(0, rowTop)
            } else if (rowBottom > scrollY + visibleHeight) {
                scrollView.smoothScrollTo(0, rowBottom - visibleHeight)
            }
        }
    }

    private fun scrollToPlaying() {
        val scrollView = musicScrollView ?: return
        val playingIndex = trackList.indexOfFirst { it.mediaId == currentlyPlayingMediaId }
        val row = trackRowViews.getOrNull(playingIndex) ?: return
        scrollView.post {
            scrollView.scrollTo(0, row.top)
        }
    }

    private fun appButtonBackground(isFocused: Boolean, isInstalled: Boolean): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8.dp().toFloat()
            setColor(inactiveBg)
            if (isFocused) setStroke(2.dp(), primaryColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8.dp().toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), bg, mask)
    }

    private fun refreshAppList() {
        appButtonViews.forEachIndexed { i, btn ->
            val entry = appEntries.getOrNull(i) ?: return@forEachIndexed
            btn.background = appButtonBackground(i == appListIndex, entry.isInstalled)
        }
    }

    private fun playButtonBackground(isFocused: Boolean): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8.dp().toFloat()
            setColor(inactiveBg)
            if (isFocused) setStroke(2.dp(), primaryColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8.dp().toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), bg, mask)
    }

    private fun refreshPlayButton() {
        val focused = musicFocus == MusicFocus.BUTTON
        playPauseButton?.background = playButtonBackground(focused)
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playPauseIcon?.setImageDrawable(ContextCompat.getDrawable(this, iconRes))
        playPauseLabel?.text = if (isPlaying) "Pause" else "Play"
    }

    private fun refreshMusicFocus() {
        refreshPlayButton()
        val listFocused = musicFocus == MusicFocus.LIST || musicFocus == MusicFocus.LIST_ENTRY
        playlistContainerView?.background = if (listFocused) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(2.dp(), primaryColor)
            }
        } else null
        refreshTrackList()
    }

    private fun getIndexAtY(rawY: Float): Int {
        val loc = IntArray(2)
        for (i in appButtonViews.indices) {
            val btn = appButtonViews[i]
            btn.getLocationOnScreen(loc)
            if (rawY < loc[1] + btn.height) return i
        }
        return (appButtonViews.size - 1).coerceAtLeast(0)
    }

    private fun swapAppButtons(from: Int, to: Int) {
        val entry = appEntries.removeAt(from)
        appEntries.add(to, entry)
        val view = appButtonViews.removeAt(from)
        appButtonViews.add(to, view)
        val container = appButtonList ?: return
        container.removeAllViews()
        appButtonViews.forEach { container.addView(it) }
        refreshAppList()
        appButtonViews[to].background = appButtonBackground(true, appEntries[to].isInstalled)
    }

    private fun scrollToSelectedApp() {
        val scrollView = appScrollView ?: return
        val btn = appButtonViews.getOrNull(appListIndex) ?: return
        scrollView.post {
            val top = btn.top
            val bottom = btn.bottom
            val scrollY = scrollView.scrollY
            val visible = scrollView.height
            if (top < scrollY) scrollView.smoothScrollTo(0, top)
            else if (bottom > scrollY + visible) scrollView.smoothScrollTo(0, bottom - visible)
        }
    }

    private fun updateNowPlaying(metadata: MediaMetadataCompat?) {
        val bitmap = metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        if (bitmap != null) coverArtView?.setImageBitmap(bitmap)
        else coverArtView?.setImageDrawable(null)

        val dur = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
        val state = mediaController?.playbackState
        updateSeekBar(state?.position ?: 0L, dur)
    }

    private fun updateSeekBar(posMs: Long, durMs: Long) {
        val fill = seekBarFill ?: return
        val weight = if (durMs > 0L) posMs.toFloat() / durMs.toFloat() else 0f
        (fill.layoutParams as? LinearLayout.LayoutParams)?.weight = weight
        fill.requestLayout()

        timeElapsed?.text = formatDuration(posMs)
        timeRemaining?.text = formatDuration((durMs - posMs).coerceAtLeast(0L))
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:%02d".format(sec)
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
                    buildPaneIfNeeded(currentPane + 1)
                    viewFlipper.inAnimation  = slideIn(fromRight = true)
                    viewFlipper.outAnimation = slideOut(toLeft = true)
                    currentPane++
                    viewFlipper.showNext()
                    refreshTitleBar()
                    if (currentPane == 1) { appListIndex = 0; refreshAppList() }
                    else if (currentPane == 2) startSensorCoordinator()
                }
                true
            }
            leftKey -> {
                if (currentPane > 0) {
                    buildPaneIfNeeded(currentPane - 1)
                    if (currentPane == 2) stopSensorCoordinator()
                    viewFlipper.inAnimation  = slideIn(fromRight = false)
                    viewFlipper.outAnimation = slideOut(toLeft = false)
                    currentPane--
                    viewFlipper.showPrevious()
                    refreshTitleBar()
                    if (currentPane == 0) { musicFocus = MusicFocus.BUTTON; refreshMusicFocus() }
                }
                true
            }
            upKey -> {
                if (currentPane == 0) {
                    when (musicFocus) {
                        MusicFocus.BUTTON -> { /* already at top */ }
                        MusicFocus.LIST -> { musicFocus = MusicFocus.BUTTON; refreshMusicFocus() }
                        MusicFocus.LIST_ENTRY -> {
                            if (musicListIndex > 0) {
                                musicListIndex--
                                refreshTrackList()
                                scrollToSelected()
                            }
                        }
                    }
                } else if (currentPane == 1 && appButtonViews.isNotEmpty() && appListIndex > 0) {
                    appListIndex--
                    refreshAppList()
                    scrollToSelectedApp()
                }
                true
            }
            downKey -> {
                if (currentPane == 0) {
                    when (musicFocus) {
                        MusicFocus.BUTTON -> { musicFocus = MusicFocus.LIST; refreshMusicFocus() }
                        MusicFocus.LIST -> { /* already at bottom */ }
                        MusicFocus.LIST_ENTRY -> {
                            if (musicListIndex < trackList.size - 1) {
                                musicListIndex++
                                refreshTrackList()
                                scrollToSelected()
                            }
                        }
                    }
                } else if (currentPane == 1 && appButtonViews.isNotEmpty() && appListIndex < appButtonViews.size - 1) {
                    appListIndex++
                    refreshAppList()
                    scrollToSelectedApp()
                }
                true
            }
            enterKey, SECONDARY_KEY_ENTER -> {
                if (currentPane == 0) {
                    when (musicFocus) {
                        MusicFocus.BUTTON -> playPauseButton?.performClick()
                        MusicFocus.LIST -> { musicFocus = MusicFocus.LIST_ENTRY; refreshMusicFocus() }
                        MusicFocus.LIST_ENTRY -> {
                            if (trackList.isNotEmpty()) {
                                mediaController?.transportControls
                                    ?.playFromMediaId(trackList[musicListIndex].mediaId, null)
                            }
                        }
                    }
                } else if (currentPane == 1) {
                    appButtonViews.getOrNull(appListIndex)?.performClick()
                }
                true
            }
            cancelKey, SECONDARY_KEY_CANCEL -> {
                if (currentPane == 0 && musicFocus == MusicFocus.LIST_ENTRY) {
                    musicFocus = MusicFocus.LIST
                    refreshMusicFocus()
                } else {
                    hideOverlay()
                }
                true
            }
            else -> false
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

    private fun makePaneSwipeListener(): View.OnTouchListener {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 40
            private val SWIPE_VELOCITY_THRESHOLD = 80

            override fun onDown(e: MotionEvent) = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val diffX = e2.x - (e1?.x ?: 0f)
                return if (Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX < 0) {
                        // Swipe left → next pane
                        if (currentPane < paneCount - 1) {
                            buildPaneIfNeeded(currentPane + 1)
                            viewFlipper.inAnimation = slideIn(fromRight = true)
                            viewFlipper.outAnimation = slideOut(toLeft = true)
                            currentPane++
                            viewFlipper.showNext()
                            refreshTitleBar()
                            if (currentPane == 2) startSensorCoordinator()
                        }
                    } else {
                        // Swipe right → previous pane
                        if (currentPane > 0) {
                            buildPaneIfNeeded(currentPane - 1)
                            if (currentPane == 2) stopSensorCoordinator()
                            viewFlipper.inAnimation = slideIn(fromRight = false)
                            viewFlipper.outAnimation = slideOut(toLeft = false)
                            currentPane--
                            viewFlipper.showPrevious()
                            refreshTitleBar()
                        }
                    }
                    true
                } else false
            }
        })
        return View.OnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        view.animate()
            .translationX(view.width.toFloat())
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { removeOverlay() }
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

    private fun createLeftRoundedBackground(
        color: Int,
        radiusDp: Int,
        strokeWidthDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        val r = radiusDp.dp().toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setColor(color)
            if (strokeWidthDp > 0) setStroke(strokeWidthDp.dp(), strokeColor)
        }
    }
}
