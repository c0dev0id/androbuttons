package com.androbuttons.panes.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.androbuttons.R
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith
import com.androbuttons.common.roundedBg

class MusicPane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "Music"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private companion object {
        const val TARGET_PLAYER_PKG  = "de.codevoid.androsnd"
        const val VIRTUAL_WINDOW     = 20
        const val SCROLL_DEBOUNCE_MS = 150L
        const val SCROLL_THRESHOLD_BOTTOM = 0.8f
        const val SCROLL_THRESHOLD_TOP    = 0.2f
    }

    // ---- Data ---------------------------------------------------------------

    private data class TrackItem(
        val mediaId: String,
        val title: String,
        val artist: String,
        val duration: Long,
        val art: android.graphics.Bitmap? = null,
        val artUri: Uri? = null
    )

    private enum class MusicFocus { BUTTON, LIST, LIST_ENTRY }

    // ---- State --------------------------------------------------------------

    private var musicFocus = MusicFocus.BUTTON
    private var musicListIndex = 0
    private var currentlyPlayingMediaId: String? = null
    private val trackList = mutableListOf<TrackItem>()
    private var virtualStart = 0
    private var pendingFolderSubscriptions = 0

    private var mediaController: MediaControllerCompat? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private var isPlaying = false

    private val seekHandler = Handler(Looper.getMainLooper())
    private val scrollWindowRunnable = Runnable { handleScrollVirtualWindow() }

    // ---- View references ----------------------------------------------------

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
    private var playerCardView: LinearLayout? = null
    private var noPlayerView: TextView? = null

    // ---- PaneContent --------------------------------------------------------

    override fun buildView(): View {
        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // --- No-player message ---
        noPlayerView = TextView(ctx).apply {
            text = "No player running"
            textSize = 14f
            setTextColor(Theme.textTertiary)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
        }
        pane.addView(noPlayerView)

        // --- Player card ---
        playerCardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Theme.playerArea, 12, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f; bottomMargin = 10.dp() }
            clipToOutline = true
        }
        val playerCard = playerCardView!!

        coverArtView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Theme.inactiveBg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        playerCard.addView(coverArtView)

        // Seekbar row
        val seekRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        timeElapsed = TextView(ctx).apply { text = "0:00"; textSize = 10f; setTextColor(Theme.textTertiary) }
        val seekTrack = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, 4.dp()).apply {
                weight = 1f; marginStart = 6.dp(); marginEnd = 6.dp()
            }
            background = roundedBg(Theme.seekTrack, 2, ctx)
        }
        seekBarFill = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply { weight = 0f }
            background = roundedBg(Theme.primary, 2, ctx)
        }
        seekTrack.addView(seekBarFill)
        seekTrack.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply { weight = 1f }
        })
        timeRemaining = TextView(ctx).apply { text = "0:00"; textSize = 10f; setTextColor(Theme.textTertiary) }
        seekRow.addView(timeElapsed); seekRow.addView(seekTrack); seekRow.addView(timeRemaining)
        playerCard.addView(seekRow)
        pane.addView(playerCard)

        // --- Play/pause button ---
        playPauseIcon = ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val size = 24.dp()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dp() }
        }
        playPauseLabel = TextView(ctx).apply {
            text = if (isPlaying) "Pause" else "Play"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        playPauseButton = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            background = buttonBg(musicFocus == MusicFocus.BUTTON, ctx)
            isClickable = true
            addView(playPauseIcon); addView(playPauseLabel)
            setOnClickListener {
                if (isPlaying) mediaController?.transportControls?.pause()
                else           mediaController?.transportControls?.play()
            }
        }
        pane.addView(playPauseButton)

        // --- Playlist section ---
        playlistContainerView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 3f }
        }
        val playlistContainer = playlistContainerView!!

        playlistContainer.addView(TextView(ctx).apply {
            text = "PLAYLIST"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTertiary)
            letterSpacing = 0.1f
            setPadding(2.dp(), 0, 2.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        musicScrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
            isVerticalScrollBarEnabled = false
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && musicFocus == MusicFocus.BUTTON) {
                    musicFocus = MusicFocus.LIST
                    refreshMusicFocus()
                }
                false
            }
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (scrollY != oldScrollY) {
                    seekHandler.removeCallbacks(scrollWindowRunnable)
                    seekHandler.postDelayed(scrollWindowRunnable, SCROLL_DEBOUNCE_MS)
                }
            }
        }
        trackListContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        musicScrollView!!.addView(trackListContainer)
        playlistContainer.addView(musicScrollView)
        pane.addView(playlistContainerView)

        trackListContainer!!.addView(TextView(ctx).apply {
            text = "♫  No tracks"
            textSize = 12f
            setTextColor(Theme.textTertiary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp() }
        })

        connectMedia()
        return pane
    }

    override fun onResumed() {
        musicFocus = MusicFocus.BUTTON
        refreshMusicFocus()
        scrollToPlaying()
    }

    override fun onDestroy() {
        seekHandler.removeCallbacks(seekUpdater)
        seekHandler.removeCallbacks(scrollWindowRunnable)
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaBrowser?.disconnect()
    }

    // ---- Key navigation -----------------------------------------------------

    override fun onUp(): Boolean {
        when (musicFocus) {
            MusicFocus.BUTTON     -> { /* already at top */ }
            MusicFocus.LIST       -> { musicFocus = MusicFocus.BUTTON; refreshMusicFocus() }
            MusicFocus.LIST_ENTRY -> {
                if (musicListIndex > 0) { musicListIndex--; refreshTrackList(); scrollToSelected() }
            }
        }
        return true
    }

    override fun onDown(): Boolean {
        when (musicFocus) {
            MusicFocus.BUTTON     -> { musicFocus = MusicFocus.LIST; refreshMusicFocus() }
            MusicFocus.LIST       -> { /* already at bottom */ }
            MusicFocus.LIST_ENTRY -> {
                if (musicListIndex < trackList.size - 1) { musicListIndex++; refreshTrackList(); scrollToSelected() }
            }
        }
        return true
    }

    override fun onEnter(): Boolean {
        when (musicFocus) {
            MusicFocus.BUTTON -> playPauseButton?.performClick()
            MusicFocus.LIST   -> {
                musicFocus = MusicFocus.LIST_ENTRY
                val playingIdx = currentlyPlayingMediaId?.let { id -> trackList.indexOfFirst { it.mediaId == id } } ?: -1
                if (playingIdx >= 0) { musicListIndex = playingIdx; ensureIndexInWindow(playingIdx) }
                refreshMusicFocus(); scrollToSelected()
            }
            MusicFocus.LIST_ENTRY -> {
                if (trackList.isNotEmpty()) {
                    mediaController?.transportControls?.playFromMediaId(trackList[musicListIndex].mediaId, null)
                }
            }
        }
        return true
    }

    override fun onCancel(): Boolean {
        if (musicFocus == MusicFocus.LIST_ENTRY) {
            musicFocus = MusicFocus.LIST
            refreshMusicFocus()
            return true   // consumed — do not close overlay
        }
        return false      // not consumed — OverlayService will close the overlay
    }

    // ---- Media connection ---------------------------------------------------

    private fun connectMedia() {
        val component = findMediaBrowserComponent(TARGET_PLAYER_PKG)
        if (component == null) { showNoPlayerMessage(true); return }
        mediaBrowser?.disconnect()
        mediaBrowser = MediaBrowserCompat(ctx, component, browserConnectionCallback, null)
        mediaBrowser!!.connect()
    }

    private fun findMediaBrowserComponent(pkg: String): ComponentName? {
        for (action in listOf(
            "android.media.browse.MediaBrowserService",
            "android.support.v4.media.browse.MediaBrowserService"
        )) {
            val info = ctx.packageManager.queryIntentServices(Intent(action), 0)
                .firstOrNull { it.serviceInfo.packageName == pkg }
            if (info != null) return ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
        }
        return null
    }

    private fun showNoPlayerMessage(show: Boolean) {
        noPlayerView?.visibility          = if (show) View.VISIBLE else View.GONE
        playerCardView?.visibility        = if (show) View.GONE    else View.VISIBLE
        playPauseButton?.visibility       = if (show) View.GONE    else View.VISIBLE
        playlistContainerView?.visibility = if (show) View.GONE    else View.VISIBLE
    }

    // ---- Media callbacks ----------------------------------------------------

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onSessionReady() {
            val metadata = mediaController?.metadata
            currentlyPlayingMediaId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
            updateNowPlaying(metadata)
            refreshTrackList()
            scrollToPlaying()
            val state = mediaController?.playbackState
            val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
            isPlaying = playing
            refreshPlayButton()
            if (playing) { seekHandler.removeCallbacks(seekUpdater); seekHandler.post(seekUpdater) }
        }
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            val newId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
            if (newId != currentlyPlayingMediaId) {
                currentlyPlayingMediaId = newId; refreshTrackList(); scrollToPlaying()
            }
            updateNowPlaying(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
            isPlaying = playing; refreshPlayButton()
            if (playing) { seekHandler.removeCallbacks(seekUpdater); seekHandler.post(seekUpdater) }
            else {
                seekHandler.removeCallbacks(seekUpdater)
                updateSeekBar(state?.position ?: 0L,
                    mediaController?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L)
            }
        }
    }

    private val browserConnectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val browser = mediaBrowser ?: return
            showNoPlayerMessage(false)
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = MediaControllerCompat(ctx, browser.sessionToken)
            mediaController!!.registerCallback(mediaControllerCallback, Handler(Looper.getMainLooper()))
            browser.subscribe(browser.root, browserSubscriptionCallback)
        }
        override fun onConnectionFailed() {
            android.util.Log.w("androbuttons", "MediaBrowser connection failed: ${mediaBrowser?.serviceComponent}")
            showNoPlayerMessage(true)
        }
        override fun onConnectionSuspended() {
            android.util.Log.w("androbuttons", "MediaBrowser connection suspended")
            mediaBrowser?.disconnect(); mediaBrowser = null
            showNoPlayerMessage(true)
        }
    }

    private val browserSubscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowserCompat.MediaItem>) {
            val playable  = children.filter { it.isPlayable }
            val browsable = children.filter { it.isBrowsable }
            if (browsable.isNotEmpty() && playable.isEmpty()) {
                trackList.clear()
                pendingFolderSubscriptions = browsable.size
                browsable.forEach { folder -> mediaBrowser?.subscribe(folder.mediaId ?: return@forEach, this) }
            } else {
                if (parentId == (mediaBrowser?.root ?: "")) trackList.clear()
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
                if (parentId != (mediaBrowser?.root ?: "")) pendingFolderSubscriptions--
                if (pendingFolderSubscriptions <= 0) { musicListIndex = 0; rebuildTrackList(resetWindow = true) }
            }
        }
    }

    private val seekUpdater = object : Runnable {
        override fun run() {
            val state = mediaController?.playbackState ?: return
            val pos = if (state.state == PlaybackStateCompat.STATE_PLAYING) {
                state.position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
            } else state.position
            val dur = mediaController?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
            updateSeekBar(pos, dur)
            seekHandler.postDelayed(this, 500)
        }
    }

    // ---- Track list UI ------------------------------------------------------

    private fun rebuildTrackList(resetWindow: Boolean = false) {
        val container = trackListContainer ?: return
        container.removeAllViews(); trackRowViews.clear()
        if (trackList.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "♫  No tracks"; textSize = 12f; setTextColor(Theme.textTertiary); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16.dp() }
            })
            return
        }
        if (resetWindow) {
            val playingIdx = currentlyPlayingMediaId?.let { id -> trackList.indexOfFirst { it.mediaId == id } } ?: -1
            val center = if (playingIdx >= 0) playingIdx else musicListIndex.coerceAtLeast(0)
            virtualStart = (center - VIRTUAL_WINDOW / 2).coerceIn(0, maxOf(0, trackList.size - VIRTUAL_WINDOW))
        }
        val end = minOf(virtualStart + VIRTUAL_WINDOW, trackList.size)
        for (index in virtualStart until end) {
            val track = trackList[index]
            val row = buildTrackRow(
                track,
                musicFocus == MusicFocus.LIST_ENTRY && index == musicListIndex,
                track.mediaId == currentlyPlayingMediaId,
                index
            )
            trackRowViews.add(row); container.addView(row)
        }
        val playingIdx = currentlyPlayingMediaId?.let { id -> trackList.indexOfFirst { it.mediaId == id } } ?: -1
        val localPlayingIdx = playingIdx - virtualStart
        if (localPlayingIdx in 0 until trackRowViews.size) {
            musicScrollView?.post { musicScrollView?.scrollTo(0, trackRowViews[localPlayingIdx].top) }
        }
    }

    private fun buildTrackRow(track: TrackItem, isFocused: Boolean, isPlaying: Boolean, index: Int): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            background = trackRowBg(isFocused, isPlaying)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2.dp() }
            isClickable = true
            setOnClickListener {
                musicListIndex = index
                mediaController?.transportControls?.playFromMediaId(track.mediaId, null)
                refreshTrackList()
            }

            val artView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val size = 36.dp()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 8.dp() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 4.dp().toFloat()
                    setColor(Color.argb(60, 255, 255, 255))
                }
                if (track.art != null) {
                    setImageBitmap(track.art)
                } else if (track.artUri != null) {
                    val uri = track.artUri; val view = this
                    Thread {
                        try {
                            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                            if (bmp != null) seekHandler.post { view.setImageBitmap(bmp) }
                        } catch (_: Exception) {}
                    }.start()
                }
            }
            addView(artView)

            val metaCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            }
            metaCol.addView(TextView(ctx).apply {
                text = track.title; textSize = 12f
                setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (track.artist.isNotEmpty()) {
                metaCol.addView(TextView(ctx).apply {
                    text = track.artist; textSize = 10f; setTextColor(Theme.textSecondary)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addView(metaCol)

            if (track.duration > 0L) {
                addView(TextView(ctx).apply {
                    text = formatDuration(track.duration); textSize = 10f; setTextColor(Theme.textTertiary)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 4.dp() }
                })
            }
        }
    }

    private fun trackRowBg(isFocused: Boolean, isPlaying: Boolean): GradientDrawable? {
        if (!isFocused && !isPlaying) return null
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6.dp().toFloat()
            setColor(if (isPlaying) Theme.playingRow else Color.TRANSPARENT)
            if (isFocused || isPlaying) setStroke(2.dp(), Theme.primary)
        }
    }

    private fun refreshTrackList() {
        trackRowViews.forEachIndexed { i, row ->
            val trackIndex = virtualStart + i
            val focused = musicFocus == MusicFocus.LIST_ENTRY && trackIndex == musicListIndex
            val playing = trackList.getOrNull(trackIndex)?.mediaId == currentlyPlayingMediaId
            row.background = trackRowBg(focused, playing)
        }
    }

    private fun refreshPlayButton() {
        val focused = musicFocus == MusicFocus.BUTTON
        playPauseButton?.background = buttonBg(focused, ctx)
        playPauseIcon?.setImageDrawable(ContextCompat.getDrawable(ctx, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play))
        playPauseLabel?.text = if (isPlaying) "Pause" else "Play"
    }

    private fun refreshMusicFocus() {
        refreshPlayButton()
        val listFocused = musicFocus == MusicFocus.LIST || musicFocus == MusicFocus.LIST_ENTRY
        playlistContainerView?.background = if (listFocused) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 8.dp().toFloat()
                setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.primary)
            }
        } else null
        refreshTrackList()
    }

    // ---- Seekbar + now-playing ----------------------------------------------

    private fun updateNowPlaying(metadata: MediaMetadataCompat?) {
        val bitmap = metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        if (bitmap != null) coverArtView?.setImageBitmap(bitmap) else coverArtView?.setImageDrawable(null)
        val dur = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
        updateSeekBar(mediaController?.playbackState?.position ?: 0L, dur)
    }

    private fun updateSeekBar(posMs: Long, durMs: Long) {
        val fill = seekBarFill ?: return
        val weight = if (durMs > 0L) posMs.toFloat() / durMs.toFloat() else 0f
        (fill.layoutParams as? LinearLayout.LayoutParams)?.weight = weight
        fill.requestLayout()
        timeElapsed?.text  = formatDuration(posMs)
        timeRemaining?.text = formatDuration((durMs - posMs).coerceAtLeast(0L))
    }

    // ---- Scroll helpers -----------------------------------------------------

    private fun ensureIndexInWindow(index: Int) {
        if (index < 0 || index >= trackList.size) return
        if (index < virtualStart || index >= virtualStart + VIRTUAL_WINDOW) {
            virtualStart = (index - VIRTUAL_WINDOW / 2).coerceIn(0, maxOf(0, trackList.size - VIRTUAL_WINDOW))
            rebuildTrackList()
        }
    }

    private fun handleScrollVirtualWindow() {
        val scrollView = musicScrollView ?: return
        val container = trackListContainer ?: return
        if (trackList.isEmpty() || trackRowViews.isEmpty()) return
        val scrollY = scrollView.scrollY
        val contentHeight = container.height
        val visibleHeight = scrollView.height
        if (contentHeight <= 0 || visibleHeight <= 0) return
        val scrollBottom = scrollY + visibleHeight
        if (scrollBottom >= contentHeight * SCROLL_THRESHOLD_BOTTOM && virtualStart + VIRTUAL_WINDOW < trackList.size) {
            val topVisibleRow = trackRowViews.indexOfFirst { it.bottom > scrollY }.coerceAtLeast(0)
            val topAbsoluteIndex = virtualStart + topVisibleRow
            val newVirtualStart = (topAbsoluteIndex - VIRTUAL_WINDOW / 2).coerceIn(0, maxOf(0, trackList.size - VIRTUAL_WINDOW))
            if (newVirtualStart > virtualStart) {
                virtualStart = newVirtualStart; rebuildTrackList()
                scrollView.post {
                    val newLocalIndex = (topAbsoluteIndex - virtualStart).coerceIn(0, trackRowViews.size - 1)
                    trackRowViews.getOrNull(newLocalIndex)?.let { scrollView.scrollTo(0, it.top) }
                }
            }
        } else if (scrollY <= contentHeight * SCROLL_THRESHOLD_TOP && virtualStart > 0) {
            val topAbsoluteIndex = virtualStart
            val newVirtualStart = maxOf(0, virtualStart - VIRTUAL_WINDOW / 2)
            if (newVirtualStart < virtualStart) {
                virtualStart = newVirtualStart; rebuildTrackList()
                scrollView.post {
                    val newLocalIndex = (topAbsoluteIndex - virtualStart).coerceIn(0, trackRowViews.size - 1)
                    trackRowViews.getOrNull(newLocalIndex)?.let { scrollView.scrollTo(0, it.top) }
                }
            }
        }
    }

    private fun scrollToSelected() {
        if (musicListIndex < 0 || musicListIndex >= trackList.size) return
        ensureIndexInWindow(musicListIndex)
        val scrollView = musicScrollView ?: return
        val localIndex = musicListIndex - virtualStart
        val row = trackRowViews.getOrNull(localIndex) ?: return
        scrollView.post {
            val rowTop = row.top; val rowBottom = row.bottom
            val scrollY = scrollView.scrollY; val visibleHeight = scrollView.height
            if (rowTop < scrollY) scrollView.smoothScrollTo(0, rowTop)
            else if (rowBottom > scrollY + visibleHeight) scrollView.smoothScrollTo(0, rowBottom - visibleHeight)
        }
    }

    private fun scrollToPlaying() {
        val scrollView = musicScrollView ?: return
        val playingIndex = trackList.indexOfFirst { it.mediaId == currentlyPlayingMediaId }
        if (playingIndex < 0) return
        ensureIndexInWindow(playingIndex)
        val localIndex = playingIndex - virtualStart
        val row = trackRowViews.getOrNull(localIndex) ?: return
        scrollView.post { scrollView.scrollTo(0, row.top) }
    }

    // ---- Utilities ----------------------------------------------------------

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSec = ms / 1000
        return "${totalSec / 60}:%02d".format(totalSec % 60)
    }
}
