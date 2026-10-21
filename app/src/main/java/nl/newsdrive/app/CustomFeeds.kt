package nl.newsdrive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Feeds added by the user in the app, stored as JSON. */
object CustomFeeds {

    private const val KEY = "custom_feeds"

    fun load(c: Context) {
        val text = AppSettings.prefs(c).getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<NewsSource>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(from(o))
            }
        } catch (e: Exception) {
            // ignore a damaged list
        }
        NewsConfig.custom = out
    }

    private fun from(o: JSONObject): NewsSource {
        val live = o.optBoolean("live")
        val url = o.getString("url")
        return NewsSource(
            id = o.getString("id"),
            name = o.getString("name"),
            description = if (live) "Live stream you added" else "Podcast feed you added",
            feedUrl = if (live) "" else url,
            streamUrl = if (live) url else null,
            liveMinutes = o.optInt("minutes", 5),
            custom = true,
        )
    }

    private fun save(c: Context, list: List<NewsSource>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.streamUrl ?: it.feedUrl)
                    .put("live", it.isLive)
                    .put("minutes", it.liveMinutes)
            )
        }
        AppSettings.prefs(c).edit().putString(KEY, arr.toString()).apply()
        NewsConfig.custom = list
    }

    fun add(c: Context, name: String, url: String, live: Boolean) {
        val id = "u" + System.currentTimeMillis()
        val src = NewsSource(
            id = id,
            name = name,
            description = if (live) "Live stream you added" else "Podcast feed you added",
            feedUrl = if (live) "" else url,
            streamUrl = if (live) url else null,
            custom = true,
        )
        save(c, NewsConfig.custom + src)
    }

    fun remove(c: Context, id: String) {
        save(c, NewsConfig.custom.filter { it.id != id })
        OfflineCache.delete(c, id)
    }
}
