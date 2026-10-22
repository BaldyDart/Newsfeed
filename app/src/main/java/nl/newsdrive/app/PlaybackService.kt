package nl.newsdrive.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Media service used by both the phone UI and Android Auto.
 *
 * Library tree:  root -> [ "Play all news", then every enabled source ]
 * Selecting "Play all news" resolves, at that moment, to the newest bulletin of every source,
 * queued in the saved feed order.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var exoPlayer: ExoPlayer
    private val main = Handler(Looper.getMainLooper())

    // Cached pool: feed downloads block on each other's results, so a bounded pool could deadlock.
    private val io: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())

    private val refreshCommand = SessionCommand(NewsConfig.ACTION_REFRESH, Bundle.EMPTY)
    private val back10Command = SessionCommand(NewsConfig.ACTION_BACK10, Bundle.EMPTY)
    private val fwd10Command = SessionCommand(NewsConfig.ACTION_FWD10, Bundle.EMPTY)
    private val autoStartCommand = SessionCommand(NewsConfig.ACTION_AUTOSTART, Bundle.EMPTY)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var announcing = false
    private var lastAutoStart = 0L

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.KEY_SPEED) main.post { applySpeed() }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("NewsNow/1.4 (Android)")
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    androidx.media3.datasource.DefaultDataSource.Factory(this, http)
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        // "Previous" always means the previous bulletin (or the start of the first one),
        // instead of the default "restart the current item if more than 3 s in".
        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun seekToPrevious() {
                if (hasPreviousMediaItem()) seekToPreviousMediaItem() else seekTo(0L)
            }
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                rescheduleLiveStop()
                applySpeed()
                markHeard(mediaItem)
                announce(mediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = rescheduleLiveStop()

            override fun onPlayerError(error: PlaybackException) = recoverFromError()
        })

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = resumeAfterAnnouncement()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = resumeAfterAnnouncement()
                })
            }
        }
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)

        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback()).build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_news)
        setMediaNotificationProvider(notificationProvider)
    }

    // A live stream never ends, so it is cut after its configured time and the queue moves on.
    private val liveStop = Runnable {
        if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNextMediaItem() else exoPlayer.stop()
    }

    private fun sourceOf(item: MediaItem?): NewsSource? =
        item?.mediaId?.takeIf { it.startsWith(NewsConfig.SOURCE_PREFIX) }
            ?.let { NewsConfig.byId(it.removePrefix(NewsConfig.SOURCE_PREFIX)) }

    private fun applySpeed() {
        val live = sourceOf(exoPlayer.currentMediaItem)?.isLive == true
        exoPlayer.setPlaybackSpeed(if (live) 1f else AppSettings.speed(this))
    }

    /** A bulletin that has started playing counts as heard, so a later refresh can skip it. */
    private fun markHeard(item: MediaItem?) {
        val src = sourceOf(item) ?: return
        if (src.isLive) return
        item?.mediaMetadata?.extras?.getString("key")?.let { AppSettings.markHeard(this, src.id, it) }
    }

    /** Says the station name before its bulletin starts (option "Announce sources"). */
    private fun announce(item: MediaItem?) {
        if (!AppSettings.announce(this) || !ttsReady || item == null || !exoPlayer.playWhenReady) return
        val name = item.mediaMetadata.title?.toString() ?: return
        announcing = true
        exoPlayer.pause()
        tts?.speak(name, TextToSpeech.QUEUE_FLUSH, null, "announce")
    }

    private fun resumeAfterAnnouncement() {
        main.post {
            if (announcing) {
                announcing = false
                exoPlayer.play()
            }
        }
    }

    /** Playback failed (tunnel, dead zone): try the offline copy, otherwise move on to the next bulletin. */
    private fun recoverFromError() {
        announcing = false
        val item = exoPlayer.currentMediaItem
        val src = sourceOf(item)
        val index = exoPlayer.currentMediaItemIndex
        val isWeb = item?.localConfiguration?.uri?.scheme?.startsWith("http") == true
        val cached = if (src != null && isWeb) OfflineCache.get(this, src) else null
        if (cached != null) {
            exoPlayer.replaceMediaItem(index, cached.toMediaItem())
            exoPlayer.prepare()
            exoPlayer.play()
        } else if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    /** Auto-start from the car: only when nothing is playing, and not twice within two minutes. */
    private fun maybeAutoStart() {
        main.post {
            val now = System.currentTimeMillis()
            val idle = !exoPlayer.isPlaying
            if (idle && now - lastAutoStart > 120_000) {
                lastAutoStart = now
                refreshAndPlay()
            }
        }
    }

    private fun rescheduleLiveStop() {
        main.removeCallbacks(liveStop)
        val id = exoPlayer.currentMediaItem?.mediaId ?: return
        val src = NewsConfig.byId(id.removePrefix(NewsConfig.SOURCE_PREFIX)) ?: return
        if (src.isLive && exoPlayer.isPlaying) {
            main.postDelayed(liveStop, AppSettings.liveMinutes(this, src) * 60_000L)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the app away from the recent apps list stops the audio and the service.
        main.removeCallbacks(liveStop)
        announcing = false
        tts?.stop()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(liveStop)
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        tts?.shutdown()
        session?.run {
            player.release()
            release()
        }
        session = null
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- feeds

    /** Newest bulletin per source, in configured order. Sources that fail are skipped. */
    private fun loadItems(sources: List<NewsSource>): MutableList<MediaItem> {
        val futures = sources.map { s ->
            io.submit(Callable<Bulletin?> {
                runCatching { FeedFetcher.latest(s) }.getOrNull() ?: OfflineCache.get(this@PlaybackService, s)
            })
        }
        var bulletins = futures.mapNotNull { it.get() }

        // Skip bulletins that were already played, unless that would leave nothing new at all.
        if (AppSettings.skipHeard(this)) {
            val fresh = bulletins.filter { !it.source.isLive && !AppSettings.isHeard(this, it) }
            if (fresh.isNotEmpty()) {
                bulletins = bulletins.filter { it.source.isLive || !AppSettings.isHeard(this, it) }
            }
        }

        // Keep a copy of each bulletin for offline use.
        val online = bulletins.filter { it.audioUrl.startsWith("http") && !it.source.isLive }
        if (online.isNotEmpty()) io.execute { online.forEach { OfflineCache.store(this, it) } }

        return bulletins.map { it.toMediaItem() }.toMutableList()
    }

    private fun resolve(requested: List<MediaItem>): MutableList<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (item in requested) {
            when {
                item.localConfiguration != null -> out.add(item) // already has a URI
                item.mediaId == NewsConfig.ALL_ID -> out.addAll(loadItems(FeedSettings.enabledSources(this@PlaybackService)))
                item.mediaId.startsWith(NewsConfig.SOURCE_PREFIX) -> {
                    val src = NewsConfig.byId(item.mediaId.removePrefix(NewsConfig.SOURCE_PREFIX))
                    if (src != null) out.addAll(loadItems(listOf(src)))
                }
                else -> {
                    // Voice request ("play BBC on News Now"): a named station, otherwise all the news.
                    val q = item.requestMetadata.searchQuery?.lowercase().orEmpty()
                    val named = FeedSettings.enabledSources(this@PlaybackService)
                        .firstOrNull { q.contains(it.name.lowercase()) || q.contains(it.id) }
                    if (named != null) out.addAll(loadItems(listOf(named)))
                    else out.addAll(loadItems(FeedSettings.enabledSources(this@PlaybackService)))
                }
            }
        }
        if (out.isEmpty()) throw IOException("No news bulletins reachable")
        return out
    }

    private fun refreshAndPlay(): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()
        io.execute {
            val items = loadItems(FeedSettings.enabledSources(this))
            main.post {
                if (items.isEmpty()) {
                    result.set(SessionResult(SessionResult.RESULT_ERROR_IO))
                } else {
                    exoPlayer.setMediaItems(items, 0, 0L)
                    exoPlayer.prepare()
                    exoPlayer.play()
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
        }
        return result
    }

    // ---------------------------------------------------------------- library

    private fun rootItem() = MediaItem.Builder()
        .setMediaId(NewsConfig.ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("News Now!")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun playAllItem() = MediaItem.Builder()
        .setMediaId(NewsConfig.ALL_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Play all news")
                .setSubtitle(FeedSettings.enabledSources(this).joinToString(" → ") { it.name })
                .setArtworkUri(Uri.parse(NewsConfig.APP_TILE))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
        )
        .build()

    private fun sourceItem(s: NewsSource): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(s.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        s.artwork?.let { meta.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder()
            .setMediaId(NewsConfig.SOURCE_PREFIX + s.id)
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun isCar(pkg: String) = pkg.contains("gearhead") || pkg.contains("android.car")

    private fun button(name: String, iconRes: Int, command: SessionCommand): CommandButton =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(name)
            .setIconResId(iconRes)
            .setSessionCommand(command)
            .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val layout = ImmutableList.of(
                button("Back 10 seconds", R.drawable.ic_replay_10, back10Command),
                button("Forward 10 seconds", R.drawable.ic_forward_10, fwd10Command),
                button("Refresh", R.drawable.ic_refresh, refreshCommand),
            )
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(refreshCommand)
                .add(back10Command)
                .add(fwd10Command)
                .add(autoStartCommand)
                .build()
            if (AppSettings.autoStart(this@PlaybackService) && isCar(controller.packageName)) maybeAutoStart()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(layout)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val ok = Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            return when (customCommand.customAction) {
                NewsConfig.ACTION_REFRESH -> refreshAndPlay()
                NewsConfig.ACTION_BACK10 -> { session.player.seekBack(); ok }
                NewsConfig.ACTION_FWD10 -> { session.player.seekForward(); ok }
                NewsConfig.ACTION_AUTOSTART -> { if (AppSettings.autoStart(this@PlaybackService)) maybeAutoStart(); ok }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != NewsConfig.ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            val children = ImmutableList.builder<MediaItem>()
                .add(playAllItem())
                .addAll(FeedSettings.enabledSources(this@PlaybackService).map { sourceItem(it) })
                .build()
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when {
                mediaId == NewsConfig.ROOT_ID -> rootItem()
                mediaId == NewsConfig.ALL_ID -> playAllItem()
                mediaId.startsWith(NewsConfig.SOURCE_PREFIX) ->
                    NewsConfig.byId(mediaId.removePrefix(NewsConfig.SOURCE_PREFIX))?.let { sourceItem(it) }
                else -> null
            }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        /** Turns "Play all news" / a single source into real, freshly fetched audio items. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            io.submit(Callable<MutableList<MediaItem>> { resolve(mediaItems) })

        /** Pressing play with an empty queue (headset button, Android Auto resume) starts the news. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            io.submit(Callable<MediaSession.MediaItemsWithStartPosition> {
                MediaSession.MediaItemsWithStartPosition(
                    resolve(listOf(playAllItem())),
                    /* startIndex = */ 0,
                    /* startPositionMs = */ 0L
                )
            })
    }
}
