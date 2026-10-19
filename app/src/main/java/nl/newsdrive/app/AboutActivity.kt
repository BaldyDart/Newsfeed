package nl.newsdrive.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val version = (try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }) ?: ""

        findViewById<TextView>(R.id.aboutApp).text = "NewsDrive $version".trim()
        findViewById<TextView>(R.id.aboutAuthor).text = AboutInfo.AUTHOR
        findViewById<TextView>(R.id.aboutYear).text = AboutInfo.YEAR

        val urlView = findViewById<TextView>(R.id.aboutUrl)
        if (AboutInfo.PROJECT_URL.isBlank()) {
            urlView.text = getString(R.string.about_not_set)
        } else {
            urlView.text = AboutInfo.PROJECT_URL
            findViewById<android.view.View>(R.id.aboutUrlRow).setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutInfo.PROJECT_URL)))
                } catch (e: ActivityNotFoundException) {
                    // No browser installed: nothing to open.
                }
            }
        }
    }
}
