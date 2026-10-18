package nl.newsdrive.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** Minimal phone UI: one big button to start the news, one to refresh. */
class MainActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var playButton: Button
    private lateinit var refreshButton: Button
    private lateinit var statusView: TextView

    private var refreshing = false
    private var errorText: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get().also { it.addListener(listener) }
            } catch (e: Exception) {
                errorText = "Cannot connect to the player"
            }
            updateUi()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onStop()
    }

    // ------------------------------------------------------------ actions

    private fun onPlayClicked() {
        val c = controller ?: return
        errorText = null
        val state = c.playbackState
        val hasQueue = c.mediaItemCount > 0 && state != Player.STATE_IDLE && state != Player.STATE_ENDED
        when {
            c.isPlaying -> c.pause()
            hasQueue -> c.play()
            else -> startNews(c)
        }
        updateUi()
    }

    private fun startNews(c: MediaController) {
        c.setMediaItem(MediaItem.Builder().setMediaId(NewsConfig.ALL_ID).build())
        c.prepare()
        c.play()
    }

    private fun onRefreshClicked() {
        val c = controller ?: return
        refreshing = true
        errorText = null
        updateUi()
        val result = c.sendCustomCommand(SessionCommand(NewsConfig.ACTION_REFRESH, Bundle.EMPTY), Bundle.EMPTY)
        result.addListener({
            refreshing = false
            val code = try { result.get().resultCode } catch (e: Exception) { -1 }
            if (code != 0) errorText = "Could not reach the news feeds"
            updateUi()
        }, MoreExecutors.directExecutor())
    }

    // ------------------------------------------------------------ UI

    private fun updateUi() {
        val c = controller
        playButton.text = if (c != null && c.isPlaying) "⏸  Pause" else "▶  Play news"
        refreshButton.isEnabled = c != null && !refreshing

        statusView.text = when {
            errorText != null -> errorText
            refreshing -> "Refreshing…"
            c == null -> "Connecting…"
            c.mediaItemCount == 0 || c.playbackState == Player.STATE_IDLE ->
                NewsConfig.SOURCES.joinToString(" → ") { it.name }
            c.playbackState == Player.STATE_ENDED -> "That was all the news.\nTap play for fresh bulletins."
            else -> {
                val md = c.mediaMetadata
                "${md.title ?: ""}\n${md.artist ?: ""}\n\n" +
                    "${c.currentMediaItemIndex + 1} / ${c.mediaItemCount}" +
                    if (c.playbackState == Player.STATE_BUFFERING) "  ·  buffering…" else ""
            }
        }
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        fun pill(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28 * dp
            setColor(color)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(48), px(24), px(24))
        }

        val title = TextView(this).apply {
            text = "NewsDrive"
            textSize = 22f
            setTextColor(Color.parseColor("#9AA7B4"))
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        playButton = Button(this).apply {
            textSize = 30f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pill(Color.parseColor("#1B6EF3"))
            setOnClickListener { onPlayClicked() }
        }
        root.addView(playButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(140)).apply {
            topMargin = px(40)
        })

        refreshButton = Button(this).apply {
            text = "↻  Refresh"
            textSize = 20f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pill(Color.parseColor("#2A333D"))
            setOnClickListener { onRefreshClicked() }
        }
        root.addView(refreshButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(72)).apply {
            topMargin = px(16)
        })

        statusView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(32)
        })

        setContentView(root)
    }
}
