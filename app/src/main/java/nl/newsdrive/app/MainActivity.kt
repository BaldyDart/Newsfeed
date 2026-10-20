package nl.newsdrive.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** Phone UI: artwork tile, seek bar and transport controls. Talks to PlaybackService via a MediaController. */
class MainActivity : AppCompatActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val ui = Handler(Looper.getMainLooper())
    private var refreshing = false
    private var dragging = false
    private var userSwiping = false
    private val cardAdapter = CardAdapter()
    private var seekMaxSeconds = 1f

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var refreshProgress: LinearProgressIndicator
    private lateinit var cards: ViewPager2
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var queueInfoView: TextView
    private lateinit var seek: Slider
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnPlay: FloatingActionButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnNext: ImageButton

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateUi()
        }

        override fun onPlayerError(error: PlaybackException) {
            showMessage("Could not play this bulletin. Try Refresh.")
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireActions()
        updateUi()

        showWelcomeOnFirstInstall()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Shown once, and only on a fresh install: updating an older version does not show it. */
    private fun showWelcomeOnFirstInstall() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("welcome_done", false)) return
        prefs.edit().putBoolean("welcome_done", true).apply()
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            return
        }
        if (info.firstInstallTime != info.lastUpdateTime) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_message)
            .setPositiveButton(R.string.welcome_ok, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get().also { it.addListener(playerListener) }
            } catch (e: Exception) {
                showMessage("Cannot connect to the player")
            }
            updateUi()
        }, ContextCompat.getMainExecutor(this))
        ui.post(ticker)
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onStop()
    }

    // ------------------------------------------------------------ setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        refreshProgress = findViewById(R.id.refreshProgress)
        cards = findViewById(R.id.cards)
        cards.adapter = cardAdapter
        CardAdapter.install(cards)
        cards.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) userSwiping = true
                else if (state == ViewPager2.SCROLL_STATE_IDLE) userSwiping = false
            }

            override fun onPageSelected(position: Int) {
                val c = controller ?: return
                if (userSwiping && hasActiveQueue(c) && position != c.currentMediaItemIndex) {
                    c.seekToDefaultPosition(position)
                    c.play()
                }
            }
        })
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        queueInfoView = findViewById(R.id.queueInfo)
        seek = findViewById(R.id.seek)
        positionView = findViewById(R.id.position)
        durationView = findViewById(R.id.duration)
        btnPrev = findViewById(R.id.btnPrev)
        btnBack = findViewById(R.id.btnBack)
        btnPlay = findViewById(R.id.btnPlay)
        btnForward = findViewById(R.id.btnForward)
        btnNext = findViewById(R.id.btnNext)
    }

    private fun wireActions() {
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    onRefreshClicked()
                    true
                }
                R.id.action_settings -> {
                    showSettingsMenu()
                    true
                }
                else -> false
            }
        }

        btnPlay.setOnClickListener { onPlayClicked() }
        btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        btnPrev.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0L)
        }
        btnBack.setOnClickListener { controller?.seekBack() }
        btnForward.setOnClickListener { controller?.seekForward() }

        seek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) positionView.text = formatTime((value * 1000).toLong())
        }
        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                controller?.seekTo((slider.value * 1000).toLong())
            }
        })
    }

    // ------------------------------------------------------------ actions

    private fun onPlayClicked() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            hasActiveQueue(c) -> c.play()
            else -> {
                c.setMediaItem(MediaItem.Builder().setMediaId(NewsConfig.ALL_ID).build())
                c.prepare()
                c.play()
            }
        }
        updateUi()
    }

    private fun onRefreshClicked() {
        val c = controller ?: return
        if (refreshing) return
        refreshing = true
        updateUi()
        val result = c.sendCustomCommand(SessionCommand(NewsConfig.ACTION_REFRESH, Bundle.EMPTY), Bundle.EMPTY)
        result.addListener({
            refreshing = false
            val code = try {
                result.get().resultCode
            } catch (e: Exception) {
                SessionResult.RESULT_ERROR_UNKNOWN
            }
            if (code != SessionResult.RESULT_SUCCESS) showMessage("Could not reach the news feeds")
            updateUi()
        }, MoreExecutors.directExecutor())
    }

    /** Gear icon: small popup with Feeds and About. */
    private fun showSettingsMenu() {
        val anchor = toolbar.findViewById<View>(R.id.action_settings) ?: toolbar
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menuInflater.inflate(R.menu.settings, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_feeds -> {
                    startActivity(Intent(this, FeedsActivity::class.java))
                    true
                }
                R.id.menu_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showMessage(text: String) {
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ UI state

    private fun hasActiveQueue(c: MediaController): Boolean =
        c.mediaItemCount > 0 &&
            c.playbackState != Player.STATE_IDLE &&
            c.playbackState != Player.STATE_ENDED

    private fun updateUi() {
        val c = controller
        val active = c != null && hasActiveQueue(c)

        refreshProgress.visibility = if (refreshing) View.VISIBLE else View.INVISIBLE
        toolbar.menu.findItem(R.id.action_refresh)?.isEnabled = c != null && !refreshing

        btnPlay.isEnabled = c != null
        btnPlay.setImageResource(if (c != null && c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription =
            getString(if (c != null && c.isPlaying) R.string.pause else R.string.play)

        setControl(btnPrev, active)
        setControl(btnBack, active)
        setControl(btnForward, active)
        setControl(btnNext, active && c != null && c.hasNextMediaItem())

        if (active && c != null) {
            val md = c.mediaMetadata
            titleView.text = md.title ?: ""
            subtitleView.text = md.artist ?: ""
            queueInfoView.text = buildString {
                append("Bulletin ${c.currentMediaItemIndex + 1} of ${c.mediaItemCount}")
                if (c.playbackState == Player.STATE_BUFFERING) append("  ·  buffering…")
            }
        } else {
            titleView.text = if (c != null && c.playbackState == Player.STATE_ENDED) "That was all the news" else "News Now!"
            subtitleView.text = if (c != null && c.playbackState == Player.STATE_ENDED) {
                "Press play for fresh bulletins"
            } else {
                FeedSettings.enabledSources(this).joinToString(" → ") { it.name }
            }
            queueInfoView.text = if (c == null) "Connecting…" else ""
        }
        updateCards(c, active)
        updateProgress()
    }

    /** Cards show the queue while playing, and the enabled stations while idle. */
    private fun updateCards(c: MediaController?, active: Boolean) {
        val urls: List<String?> = if (active && c != null) {
            (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaMetadata.artworkUri?.toString() }
        } else {
            FeedSettings.enabledSources(this).map { it.artwork }
        }
        cardAdapter.submit(urls)
        if (active && c != null && !userSwiping) {
            val i = c.currentMediaItemIndex
            if (i in urls.indices && cards.currentItem != i) cards.setCurrentItem(i, true)
        }
    }

    private fun setControl(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.38f
    }

    private fun updateProgress() {
        val c = controller
        if (c == null || dragging) return
        val duration = c.duration
        val playable = hasActiveQueue(c) && duration != C.TIME_UNSET && duration > 0
        if (!playable) {
            seek.isEnabled = false
            seek.value = 0f
            positionView.text = formatTime(0)
            durationView.text = "--:--"
            return
        }
        val max = maxOf(duration / 1000f, 1f)
        if (max != seekMaxSeconds) {
            seekMaxSeconds = max
            seek.valueTo = max
        }
        seek.isEnabled = true
        val pos = c.currentPosition.coerceAtLeast(0L)
        seek.value = minOf(pos / 1000f, max)
        positionView.text = formatTime(pos)
        durationView.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
