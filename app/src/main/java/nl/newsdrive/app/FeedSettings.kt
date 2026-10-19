package nl.newsdrive.app

import android.content.Context

/** Which feeds are switched on. Saved on the device; feeds are on by default unless marked otherwise. */
object FeedSettings {

    private const val PREFS = "feeds"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("enabled_$id", NewsConfig.byId(id)?.defaultOn ?: true)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled_$id", enabled).apply()
    }

    /** Enabled sources in playback order. Never empty: if everything is off, all feeds are used. */
    fun enabledSources(context: Context): List<NewsSource> =
        NewsConfig.SOURCES.filter { isEnabled(context, it.id) }.ifEmpty { NewsConfig.SOURCES.filter { s -> s.defaultOn } }
}
