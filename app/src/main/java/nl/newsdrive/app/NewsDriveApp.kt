package nl.newsdrive.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class NewsDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CustomFeeds.load(this)
        // Android 12+: colours follow the wallpaper, like other Google apps. Older versions use the theme colours.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
