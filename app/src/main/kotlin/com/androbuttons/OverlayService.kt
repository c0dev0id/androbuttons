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
import android.graphics.drawable.GradientDrawable
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

        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // Pane state
    private var currentPane = 0
    private val paneCount = 2
    private val paneNames = arrayOf("Music", "Apps")

    // Media player state
    private var isPlaying = false

    // Music pane state
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
    private var playPauseButton: ImageView? = null

    // Stored view references for direct updates (no full rebuild)
    private lateinit var viewFlipper: ViewFlipper
    private var titleArrowLeft: TextView? = null
    private var titleText: TextView? = null
    private var titleArrowRight: TextView? = null

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

    private val overlayWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.25f).toInt()

    private val overlayHeight: Int
        get() {
            val screenH = resources.displayMetrics.heightPixels
            val usableH = screenH - statusBarHeight - navBarHeight
            return (usableH * 0.90f).toInt()
        }

    // --- Data ---

    private data class TrackItem(
        val mediaId: String,
        val title: String,
        val artist: String,
        val duration: Long,  // ms
        val art: android.graphics.Bitmap? = null
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
            val iconRes = if (playing) R.drawable.ic_pause else R.drawable.ic_play
            playPauseButton?.setImageDrawable(ContextCompat.getDrawable(this@OverlayService, iconRes))
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
            }
            updateNowPlaying(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
            isPlaying = playing
            val iconRes = if (playing) R.drawable.ic_pause else R.drawable.ic_play
            playPauseButton?.setImageDrawable(ContextCompat.getDrawable(this@OverlayService, iconRes))
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
                        art      = desc.iconBitmap
                    ))
                }
                if (parentId != (mediaBrowser?.root ?: "")) {
                    pendingFolderSubscriptions--
                }
                if (pendingFolderSubscriptions <= 0) {
                    musicListIndex = 0
                    rebuildTrackList()
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
        if (isRunning) {
            exitWithAnimation()
        } else {
            isRunning = true
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        seekHandler.removeCallbacks(seekUpdater)
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaBrowser?.disconnect()
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
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 20.dp()
            // Shift y to center within usable area (between status bar and nav bar)
            y = (statusBarHeight - navBarHeight) / 2
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
        connectMedia()
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

    private fun connectMedia() {
        val component = findMediaBrowserComponent(TARGET_PLAYER_PKG) ?: return
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
            if (event.action == MotionEvent.ACTION_OUTSIDE) { exitWithAnimation(); true }
            else false
        }

        return container
    }

    private fun buildTitleBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerColor)
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleArrowLeft = TextView(this).apply {
            text = "◀"
            textSize = 13f
            setTextColor(if (currentPane > 0) primaryColor else inactiveBg)
            setPadding(0, 0, 8.dp(), 0)
        }

        titleText = TextView(this).apply {
            text = paneNames[currentPane]
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        titleArrowRight = TextView(this).apply {
            text = "▶"
            textSize = 13f
            setTextColor(if (currentPane < paneCount - 1) primaryColor else inactiveBg)
            setPadding(8.dp(), 0, 0, 0)
        }

        titleArrowLeft?.setOnClickListener {
            if (currentPane > 0) {
                viewFlipper.inAnimation  = slideIn(fromRight = false)
                viewFlipper.outAnimation = slideOut(toLeft = false)
                currentPane--
                viewFlipper.showPrevious()
                refreshTitleBar()
            }
        }

        titleArrowRight?.setOnClickListener {
            if (currentPane < paneCount - 1) {
                viewFlipper.inAnimation  = slideIn(fromRight = true)
                viewFlipper.outAnimation = slideOut(toLeft = true)
                currentPane++
                viewFlipper.showNext()
                refreshTitleBar()
            }
        }

        bar.addView(titleArrowLeft)
        bar.addView(titleText)
        bar.addView(titleArrowRight)

        bar.isClickable = true
        bar.setOnTouchListener(makePaneSwipeListener())

        return bar
    }

    private fun refreshTitleBar() {
        titleText?.text = paneNames[currentPane]
        titleArrowLeft?.setTextColor(if (currentPane > 0) primaryColor else inactiveBg)
        titleArrowRight?.setTextColor(if (currentPane < paneCount - 1) primaryColor else inactiveBg)
    }

    private fun buildFlipperView(): ViewFlipper {
        viewFlipper = ViewFlipper(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        viewFlipper.addView(buildMusicPane())
        viewFlipper.addView(buildLauncherPane())
        viewFlipper.displayedChild = currentPane
        return viewFlipper
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
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
            clipToOutline = true
        }

        // Full-width cover art
        coverArtView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(inactiveBg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        // Make the cover square by matching height to width after layout
        coverArtView!!.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val w = right - left
            if (w > 0 && v.layoutParams.height != w) {
                v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).also { it.height = w; it.weight = 0f }
                v.requestLayout()
            }
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

        // Full-width play/pause button
        playPauseButton = ImageView(this).apply {
            val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            setImageDrawable(ContextCompat.getDrawable(this@OverlayService, iconRes))
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dp()
            )
            setOnClickListener {
                if (isPlaying) {
                    mediaController?.transportControls?.pause()
                } else {
                    mediaController?.transportControls?.play()
                }
            }
        }
        playerCard.addView(playPauseButton)

        pane.addView(playerCard)

        // --- Playlist section ---
        pane.addView(TextView(this).apply {
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
        pane.addView(musicScrollView)

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

    private fun buildLauncherPane(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(makePaneSwipeListener())
            
            addView(buildAppButton("Voice Note", "com.voicenotes.main"))
            addView(buildAppButton("Ridelink", "otp.systems.ridelink"))
            addView(buildAppButton("Cardo Connect", "com.cardo.smartset"))
            addView(buildAppButton("Sena +Mesh", "com.sena.plusmesh"))
            addView(buildAppButton("Telegram", "org.telegram.messenger"))
            addView(buildAppButton("WhatsApp", "com.whatsapp"))
            addView(buildAppButton("Discord", "com.discord"))
            addView(buildAppButton("Blitzer.de", "de.blitzer.plus"))
            addView(buildAppButton("MeteoBlue", "com.meteoblue.droid"))
            addView(buildAppButton("PACE Drive", "car.pace.drive"))
            addView(buildAppButton("ryd", "com.thinxnet.native_tanktaler_android"))
            addView(buildAppButton("Google Drive", "com.google.android.apps.docs"))
            addView(buildAppButton("Google Photos", "com.google.android.apps.photos"))
            addView(buildAppButton("Google Maps", "com.google.android.apps.maps"))
            addView(buildAppButton("DMD2", "com.thorkracing.dmd2launcher"))
        }
    }

    private fun buildAppButton(label: String, packageName: String): TextView {
        val isInstalled = packageManager.getLaunchIntentForPackage(packageName) != null
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (isInstalled) Color.WHITE else secondaryText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.dp().toFloat()
                setColor(if (isInstalled) primaryColor else inactiveBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = isInstalled
            if (isInstalled) {
                setOnClickListener {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent != null) {
                        startActivity(intent)
                        exitWithAnimation()
                    }
                }
            }
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
            val row = buildTrackRow(track, index == musicListIndex, track.mediaId == currentlyPlayingMediaId, index)
            trackRowViews.add(row)
            container.addView(row)
        }
    }

    private fun trackRowBackground(isFocused: Boolean, isPlaying: Boolean): GradientDrawable? {
        if (!isFocused && !isPlaying) return null
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6.dp().toFloat()
            setColor(if (isPlaying) playingRow else Color.TRANSPARENT)
            if (isFocused) setStroke(2.dp(), primaryColor)
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
                if (track.art != null) setImageBitmap(track.art)
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
            val focused = i == musicListIndex
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
                    viewFlipper.inAnimation  = slideIn(fromRight = true)
                    viewFlipper.outAnimation = slideOut(toLeft = true)
                    currentPane++
                    viewFlipper.showNext()
                    refreshTitleBar()
                }
                true
            }
            leftKey -> {
                if (currentPane > 0) {
                    viewFlipper.inAnimation  = slideIn(fromRight = false)
                    viewFlipper.outAnimation = slideOut(toLeft = false)
                    currentPane--
                    viewFlipper.showPrevious()
                    refreshTitleBar()
                }
                true
            }
            upKey -> {
                if (currentPane == 0 && trackList.isNotEmpty() && musicListIndex > 0) {
                    musicListIndex--
                    refreshTrackList()
                    scrollToSelected()
                }
                true
            }
            downKey -> {
                if (currentPane == 0 && trackList.isNotEmpty() && musicListIndex < trackList.size - 1) {
                    musicListIndex++
                    refreshTrackList()
                    scrollToSelected()
                }
                true
            }
            enterKey, SECONDARY_KEY_ENTER -> {
                if (currentPane == 0 && trackList.isNotEmpty()) {
                    mediaController?.transportControls
                        ?.playFromMediaId(trackList[musicListIndex].mediaId, null)
                }
                true
            }
            cancelKey, SECONDARY_KEY_CANCEL -> {
                exitWithAnimation()
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
                            viewFlipper.inAnimation = slideIn(fromRight = true)
                            viewFlipper.outAnimation = slideOut(toLeft = true)
                            currentPane++
                            viewFlipper.showNext()
                            refreshTitleBar()
                        }
                    } else {
                        // Swipe right → previous pane
                        if (currentPane > 0) {
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

    private fun exitWithAnimation() {
        val view = overlayView ?: run { stopSelf(); return }
        view.animate()
            .translationX(view.width.toFloat())
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { stopSelf() }
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
