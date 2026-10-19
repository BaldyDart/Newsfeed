package nl.newsdrive.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/** Lists every news feed with an on/off switch. Order of the list = playback order. */
class FeedsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feeds)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val root = findViewById<View>(R.id.root)
        val list = findViewById<LinearLayout>(R.id.feedList)
        val inflater = LayoutInflater.from(this)

        for (source in NewsConfig.SOURCES) {
            val row = inflater.inflate(R.layout.item_feed, list, false)
            row.findViewById<TextView>(R.id.feedName).text = source.name
            row.findViewById<TextView>(R.id.feedDescription).text = source.description

            val toggle = row.findViewById<MaterialSwitch>(R.id.feedSwitch)
            toggle.isChecked = FeedSettings.isEnabled(this, source.id)
            toggle.setOnCheckedChangeListener { button, checked ->
                val othersOn = NewsConfig.SOURCES.count { it.id != source.id && FeedSettings.isEnabled(this, it.id) }
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
}
