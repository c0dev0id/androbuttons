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
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
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

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // Pane state
    private var currentPane = 0
    private val paneCount = 2
    private val paneNames = arrayOf("Music", "Pane 2")

    // Media player state
    private var mediaCtrlIndex = 0   // 0=Prev, 1=Play/Pause, 2=Next
    private var isPlaying = false

    // Music pane focus: 0=controls, 1=tracklist
    private var musicFocusSection = 1

    // Music pane state
    private var musicListIndex = 0
    private val trackList = mutableListOf<TrackItem>()
    private var mediaController: MediaControllerCompat? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private val seekHandler = Handler(Looper.getMainLooper())
    private var musicScrollView: ScrollView? = null
    private var trackListContainer: LinearLayout? = null
    private val trackRowViews = mutableListOf<LinearLayout>()
    private var seekBarFill: View? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var timeElapsed: TextView? = null
    private var timeRemaining: TextView? = null

    // Stored view references for direct updates (no full rebuild)
    private lateinit var viewFlipper: ViewFlipper
    private val controlViews = arrayOfNulls<ImageView>(3)
    private var titleArrowLeft: TextView? = null
    private var titleText: TextView? = null
    private var titleArrowRight: TextView? = null

    // androsnd-inspired color palette
    private val primaryColor   = Color.parseColor("#F57C00")   // orange
    private val surfaceColor   = Color.parseColor("#CC2B2B2B") // ~80% dark gray
    private val secondaryText  = Color.parseColor("#B0B0B0")
    private val tertiaryText   = Color.parseColor("#808080")
    private val inactiveBg     = Color.parseColor("#444444")
    private val seekTrackColor = Color.parseColor("#555555")
    private val selectedRow    = Color.parseColor("#80F57C00") // 50% orange

    private val overlayWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.25f).toInt()

    private val overlayHeight: Int
        get() = (resources.displayMetrics.heightPixels * 0.95f).toInt()

    // --- Data ---

    private data class TrackItem(
        val mediaId: String,
        val title: String,
        val artist: String,
        val duration: Long   // ms
    )

    // --- Media callbacks ---

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            updateNowPlaying(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            if (state?.state == PlaybackStateCompat.STATE_PLAYING) {
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
            mediaBrowser?.subscribe(mediaBrowser!!.root, browserSubscriptionCallback)
        }
    }

    private val browserSubscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: List<MediaBrowserCompat.MediaItem>
        ) {
            trackList.clear()
            children.forEach { item ->
                val desc = item.description
                trackList.add(TrackItem(
                    mediaId = item.mediaId ?: "",
                    title = desc.title?.toString() ?: "Unknown",
                    artist = desc.subtitle?.toString() ?: "",
                    duration = desc.extras?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
                ))
            }
            musicListIndex = 0
            rebuildTrackList()
        }
    }

    private val seekUpdater = object : Runnable {
        override fun run() {
            val state = mediaController?.playbackState ?: return
            val pos = state.position
            val dur = mediaController?.metadata
                ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
            updateSeekBar(pos, dur)
            seekHandler.postDelayed(this, 500)
        }
    }

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
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
        val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(this, MediaListenerService::class.java)
        val sessions = try { sessionManager.getActiveSessions(cn) } catch (_: Exception) { emptyList() }
        val session = sessions.firstOrNull() ?: return

        @Suppress("DEPRECATION")
        val token = MediaSessionCompat.Token.fromToken(session.sessionToken)
        mediaController = MediaControllerCompat(this, token)
        mediaController!!.registerCallback(mediaControllerCallback)
        updateNowPlaying(mediaController!!.metadata)
        val state = mediaController!!.playbackState
        if (state?.state == PlaybackStateCompat.STATE_PLAYING) {
            seekHandler.post(seekUpdater)
        }

        // Attempt MediaBrowser connection to browse tracks
        val pkg = session.packageName
        val browserIntent = Intent("android.media.browse.MediaBrowserService")
        val resolveInfos = packageManager.queryIntentServices(browserIntent, 0)
        val component = resolveInfos
            .firstOrNull { it.serviceInfo.packageName == pkg }
            ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            ?: return
        mediaBrowser = MediaBrowserCompat(this, component, browserConnectionCallback, null)
        mediaBrowser!!.connect()
    }

    // --- UI Building ---

    private fun buildRootView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            background = createLeftRoundedBackground(surfaceColor, 20)
            elevation = 8.dp().toFloat()
        }

        container.addView(buildTitleBar())

        // 1dp divider
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
            ).apply { bottomMargin = 8.dp() }
            setBackgroundColor(seekTrackColor)
        })

        container.addView(buildFlipperView())

        container.isFocusable = true
        container.isFocusableInTouchMode = true
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(keyCode) else false
        }

        return container
    }

    private fun buildTitleBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
        }

        titleArrowLeft = TextView(this).apply {
            text = "◀"
            textSize = 12f
            setTextColor(if (currentPane > 0) primaryColor else inactiveBg)
            setPadding(2.dp(), 4.dp(), 6.dp(), 4.dp())
        }

        titleText = TextView(this).apply {
            text = paneNames[currentPane]
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
        }

        titleArrowRight = TextView(this).apply {
            text = "▶"
            textSize = 12f
            setTextColor(if (currentPane < paneCount - 1) primaryColor else inactiveBg)
            setPadding(6.dp(), 4.dp(), 2.dp(), 4.dp())
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
        viewFlipper.addView(buildEmptyPane("Pane 2"))
        viewFlipper.displayedChild = currentPane
        return viewFlipper
    }

    private fun buildMusicPane(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Now-playing row: cover art thumbnail + title/artist
        val nowPlayingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }

        val artBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6.dp().toFloat()
                setColor(Color.argb(60, 255, 255, 255))
            }
            val size = 48.dp()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 8.dp() }
        }
        artBox.addView(TextView(this).apply {
            text = "\uD83C\uDFB5"
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        })
        nowPlayingRow.addView(artBox)

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        nowPlayingTitle = TextView(this).apply {
            text = "Not Playing"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nowPlayingArtist = TextView(this).apply {
            text = "\u2014"
            textSize = 11f
            setTextColor(secondaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        metaCol.addView(nowPlayingTitle)
        metaCol.addView(nowPlayingArtist)
        nowPlayingRow.addView(metaCol)
        pane.addView(nowPlayingRow)

        // Seekbar track
        val seekTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 4.dp()
            ).apply { bottomMargin = 4.dp() }
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
        pane.addView(seekTrack)

        // Time row
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }
        timeElapsed = TextView(this).apply {
            text = "0:00"
            textSize = 10f
            setTextColor(tertiaryText)
        }
        timeRemaining = TextView(this).apply {
            text = "0:00"
            textSize = 10f
            setTextColor(tertiaryText)
        }
        timeRow.addView(timeElapsed)
        timeRow.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0).apply { weight = 1f }
        })
        timeRow.addView(timeRemaining)
        pane.addView(timeRow)

        // Media controls: ⏮ ▶/⏸ ⏭
        pane.addView(buildMediaControls())

        // Divider
        pane.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
            ).apply { topMargin = 8.dp(); bottomMargin = 8.dp() }
            setBackgroundColor(seekTrackColor)
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

    private fun buildMediaControls(): LinearLayout {
        val drawableIds = listOf(
            R.drawable.ic_previous,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            R.drawable.ic_next
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        drawableIds.forEachIndexed { i, res ->
            val isSelected = i == mediaCtrlIndex
            val iv = ImageView(this).apply {
                setImageResource(res)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val btnSize = 48.dp()
                layoutParams = LinearLayout.LayoutParams(0, btnSize).apply {
                    weight = 1f
                    marginStart = 4.dp()
                    marginEnd = 4.dp()
                }
                background = if (isSelected)
                    createRoundedBackground(primaryColor, 8)
                else
                    createRoundedBackground(inactiveBg, 8)
            }
            controlViews[i] = iv
            row.addView(iv)
        }
        return row
    }

    private fun buildEmptyPane(label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
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
            val row = buildTrackRow(track, index == musicListIndex)
            trackRowViews.add(row)
            container.addView(row)
        }
    }

    private fun buildTrackRow(track: TrackItem, selected: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            background = if (selected) createRoundedBackground(selectedRow, 6) else null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2.dp() }

            // Cover art placeholder
            val artBox = FrameLayout(this@OverlayService).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4.dp().toFloat()
                    setColor(Color.argb(60, 255, 255, 255))
                }
                val size = 36.dp()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 8.dp() }
            }
            artBox.addView(TextView(this@OverlayService).apply {
                text = "\uD83C\uDFB5"
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            })
            addView(artBox)

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
            row.background = if (i == musicListIndex) createRoundedBackground(selectedRow, 6) else null
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
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Not Playing"
        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST)
            ?: "\u2014"
        val dur = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L

        nowPlayingTitle?.text = title
        nowPlayingArtist?.text = artist

        val state = mediaController?.playbackState
        updateSeekBar(state?.position ?: 0L, dur)
    }

    private fun updateSeekBar(posMs: Long, durMs: Long) {
        val fill = seekBarFill ?: return
        val weight = if (durMs > 0L) posMs.toFloat() / durMs.toFloat() else 0f
        (fill.layoutParams as? LinearLayout.LayoutParams)?.weight = weight
        fill.requestLayout()

        timeElapsed?.text = formatDuration(posMs)
        timeRemaining?.text = formatDuration(durMs)
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
                    if (currentPane == 0) musicFocusSection = 1
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
                when (currentPane) {
                    0 -> when (musicFocusSection) {
                        0 -> {
                            mediaCtrlIndex = (mediaCtrlIndex - 1 + 3) % 3
                            refreshMediaControls()
                        }
                        else -> if (trackList.isNotEmpty()) {
                            if (musicListIndex > 0) {
                                musicListIndex--
                                refreshTrackList()
                                scrollToSelected()
                            } else {
                                musicFocusSection = 0
                                mediaCtrlIndex = 2
                                refreshMediaControls()
                            }
                        }
                    }
                }
                true
            }
            downKey -> {
                when (currentPane) {
                    0 -> when (musicFocusSection) {
                        0 -> if (mediaCtrlIndex < 2) {
                            mediaCtrlIndex++
                            refreshMediaControls()
                        } else {
                            musicFocusSection = 1
                            musicListIndex = 0
                            refreshTrackList()
                        }
                        else -> if (trackList.isNotEmpty()) {
                            musicListIndex = (musicListIndex + 1) % trackList.size
                            refreshTrackList()
                            scrollToSelected()
                        }
                    }
                }
                true
            }
            enterKey -> {
                when (currentPane) {
                    0 -> when (musicFocusSection) {
                        0 -> activateMediaControl()
                        else -> if (trackList.isNotEmpty()) {
                            mediaController?.transportControls
                                ?.playFromMediaId(trackList[musicListIndex].mediaId, null)
                        }
                    }
                }
                true
            }
            cancelKey -> {
                exitWithAnimation()
                true
            }
            else -> false
        }
    }

    private fun activateMediaControl() {
        if (mediaCtrlIndex == 1) {
            isPlaying = !isPlaying
            (controlViews[1])?.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
        // Prev (0) and Next (2): reserved for media key dispatch in a future update
    }

    private fun refreshMediaControls() {
        controlViews.forEachIndexed { i, view ->
            val isSelected = i == mediaCtrlIndex
            view?.background = if (isSelected)
                createRoundedBackground(primaryColor, 8)
            else
                createRoundedBackground(inactiveBg, 8)
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
                            refreshIndicator()
                        }
                    } else {
                        // Swipe right → previous pane
                        if (currentPane > 0) {
                            viewFlipper.inAnimation = slideIn(fromRight = false)
                            viewFlipper.outAnimation = slideOut(toLeft = false)
                            currentPane--
                            viewFlipper.showPrevious()
                            refreshIndicator()
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

    private fun createLeftRoundedBackground(color: Int, radiusDp: Int): GradientDrawable {
        val r = radiusDp.dp().toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setColor(color)
        }
    }
}
