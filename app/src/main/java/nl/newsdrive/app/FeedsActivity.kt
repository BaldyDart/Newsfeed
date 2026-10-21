package nl.newsdrive.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/** Lists every news feed: on/off switch, order (arrows), live stream length, add and remove. */
class FeedsActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feeds)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        root = findViewById(R.id.root)
        list = findViewById(R.id.feedList)
        findViewById<View>(R.id.addFeed).setOnClickListener { showAddDialog() }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val sources = AppSettings.ordered(this)

        sources.forEachIndexed { index, source ->
            val row = inflater.inflate(R.layout.item_feed, list, false)
            row.findViewById<TextView>(R.id.feedName).text = source.name
            row.findViewById<TextView>(R.id.feedDescription).text = source.description

            val up = row.findViewById<ImageButton>(R.id.feedUp)
            val down = row.findViewById<ImageButton>(R.id.feedDown)
            up.isEnabled = index > 0
            up.alpha = if (index > 0) 1f else 0.3f
            down.isEnabled = index < sources.size - 1
            down.alpha = if (index < sources.size - 1) 1f else 0.3f
            up.setOnClickListener { move(sources, index, index - 1) }
            down.setOnClickListener { move(sources, index, index + 1) }

            if (source.isLive) {
                val minutes = row.findViewById<TextView>(R.id.feedMinutes)
                minutes.visibility = View.VISIBLE
                minutes.text = getString(R.string.live_length, AppSettings.liveMinutes(this, source))
                minutes.setOnClickListener { chooseMinutes(source) }
            }

            if (source.custom) {
                val delete = row.findViewById<ImageButton>(R.id.feedDelete)
                delete.visibility = View.VISIBLE
                delete.setOnClickListener { confirmDelete(source) }
            }

            val toggle = row.findViewById<MaterialSwitch>(R.id.feedSwitch)
            toggle.isChecked = FeedSettings.isEnabled(this, source.id)
            toggle.setOnCheckedChangeListener { button, checked ->
                val othersOn = NewsConfig.sources.count { it.id != source.id && FeedSettings.isEnabled(this, it.id) }
                if (!checked && othersOn == 0) {
                    // At least one feed must stay on.
                    button.isChecked = true
                    Snackbar.make(root, getString(R.string.keep_one_feed), Snackbar.LENGTH_SHORT).show()
                } else {
                    FeedSettings.setEnabled(this, source.id, checked)
                }
            }
            row.setOnClickListener { toggle.toggle() }
            list.addView(row)
        }
    }

    private fun move(sources: List<NewsSource>, from: Int, to: Int) {
        val ids = sources.map { it.id }.toMutableList()
        val id = ids.removeAt(from)
        ids.add(to, id)
        AppSettings.saveOrder(this, ids)
        render()
    }

    private fun chooseMinutes(source: NewsSource) {
        val options = AppSettings.LIVE_MINUTES
        val labels = options.map { "$it min" }.toTypedArray()
        val current = options.indexOf(AppSettings.liveMinutes(this, source))
        MaterialAlertDialogBuilder(this)
            .setTitle(source.name)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                AppSettings.setLiveMinutes(this, source.id, options[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(source: NewsSource) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_feed)
            .setMessage(source.name)
            .setPositiveButton(R.string.delete_feed) { _, _ ->
                CustomFeeds.remove(this, source.id)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_feed, null)
        val name = view.findViewById<EditText>(R.id.newName)
        val url = view.findViewById<EditText>(R.id.newUrl)
        val live = view.findViewById<RadioButton>(R.id.typeLive)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_feed_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
                    Snackbar.make(root, getString(R.string.bad_feed), Snackbar.LENGTH_LONG).show()
                } else {
                    CustomFeeds.add(this, n, u, live.isChecked)
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }
}
