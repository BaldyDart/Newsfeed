package nl.newsdrive.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
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
 * Library tree:  root -> [ "Play all news", ANP, BNR, NPR ]
 * Selecting "Play all news" resolves, at that moment, to the newest bulletin of every source,
 * queued in NewsConfig.SOURCES order.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())

    // Cached pool: feed downloads block on each other's results, so a bounded pool could deadlock.
    private val io: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())

    private val refreshCommand = SessionCommand(NewsConfig.ACTION_REFRESH, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
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
            io.submit(Callable<Bulletin?> { runCatching { FeedFetcher.latest(s) }.getOrNull() })
        }
        return futures.mapNotNull { it.get() }.map { it.toMediaItem() }.toMutableList()
    }

    private fun resolve(requested: List<MediaItem>): MutableList<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (item in requested) {
            when {
                item.localConfiguration != null -> out.add(item) // already has a URI
                item.mediaId == NewsConfig.ALL_ID -> out.addAll(loadItems(NewsConfig.SOURCES))
                item.mediaId.startsWith(NewsConfig.SOURCE_PREFIX) -> {
                    val src = NewsConfig.byId(item.mediaId.removePrefix(NewsConfig.SOURCE_PREFIX))
                    if (src != null) out.addAll(loadItems(listOf(src)))
                }
            }
        }
        if (out.isEmpty()) throw IOException("No news bulletins reachable")
        return out
    }

    private fun refreshAndPlay(): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()
        io.execute {
            val items = loadItems(NewsConfig.SOURCES)
            main.post {
                if (items.isEmpty()) {
                    result.set(SessionResult(SessionResult.RESULT_ERROR_IO))
                } else {
                    player.setMediaItems(items, 0, 0L)
                    player.prepare()
                    player.play()
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
                .setTitle("NewsDrive")
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
                .setSubtitle(NewsConfig.SOURCES.joinToString(" → ") { it.name })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
        )
        .build()

    private fun sourceItem(s: NewsSource) = MediaItem.Builder()
        .setMediaId(NewsConfig.SOURCE_PREFIX + s.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.name)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build()
        )
        .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val refreshButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName("Refresh")
                .setIconResId(R.drawable.ic_refresh)
                .setSessionCommand(refreshCommand)
                .build()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(refreshCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(ImmutableList.of(refreshButton))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> =
            if (customCommand.customAction == NewsConfig.ACTION_REFRESH) {
                refreshAndPlay()
            } else {
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
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
                .addAll(NewsConfig.SOURCES.map { sourceItem(it) })
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
