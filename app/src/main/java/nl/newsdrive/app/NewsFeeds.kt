package nl.newsdrive.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL

data class NewsSource(val id: String, val name: String, val feedUrl: String)

/** One resolved bulletin: the newest audio episode of a source. */
data class Bulletin(val source: NewsSource, val episodeTitle: String, val audioUrl: String)

object NewsConfig {
    const val ROOT_ID = "root"
    const val ALL_ID = "all"
    const val SOURCE_PREFIX = "src:"
    const val ACTION_REFRESH = "nl.newsdrive.REFRESH"

    /**
     * Playback order = list order. To change a station, edit the feedUrl here.
     * Each feed must be a podcast RSS feed whose newest <item> carries an audio <enclosure>.
     */
    val SOURCES = listOf(
        // ANP radio news bulletin as produced for 538 (hourly).
        NewsSource(
            id = "anp",
            name = "ANP Journaal",
            feedUrl = "https://www.omnycontent.com/d/playlist/56ccbbb7-0ff7-4482-9d99-a88800f49f6c/" +
                "25c8f040-d23c-4776-b211-a8e3009d004d/6ec59e7c-895f-4ed5-8c28-a8e3009d51ca/podcast.rss"
        ),
        // BNR Nieuwsupdate (every 30 minutes).
        NewsSource(
            id = "bnr",
            name = "BNR Nieuwsupdate",
            feedUrl = "https://www.omnycontent.com/d/playlist/8257a063-6be9-42fa-b892-acd4013b1255/" +
                "84d4de9f-8ca2-40c5-8d33-b0ce00d6f186/e4c79cb6-51a3-4c08-9573-b0ce00d746db/podcast.rss"
        ),
        // NPR News Now (hourly, ~5 minutes).
        NewsSource(
            id = "npr",
            name = "NPR News Now",
            feedUrl = "https://feeds.npr.org/500005/podcast.xml"
        ),
    )

    fun byId(id: String): NewsSource? = SOURCES.firstOrNull { it.id == id }
}

object FeedFetcher {

    /** Downloads just enough of the feed to find the newest playable episode. Returns null on failure. */
    fun latest(source: NewsSource): Bulletin? {
        val conn = (URL(source.feedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NewsDrive/1.0 (Android)")
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)
                parseFirstEpisode(parser, source)
            }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFirstEpisode(parser: XmlPullParser, source: NewsSource): Bulletin? {
        var inItem = false
        var title = ""
        var audioUrl = ""
        var isTrailer = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""
                        audioUrl = ""
                        isTrailer = false
                    }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "itunes:episodeType" -> if (inItem) {
                        isTrailer = parser.nextText().trim().equals("trailer", ignoreCase = true)
                    }
                    "enclosure" -> if (inItem) {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url").orEmpty()
                        if (url.isNotEmpty() && (type.isEmpty() || type.startsWith("audio"))) audioUrl = url
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    if (audioUrl.isNotEmpty() && !isTrailer) {
                        return Bulletin(source, title.ifEmpty { source.name }, audioUrl)
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }
        return null
    }
}

fun Bulletin.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(NewsConfig.SOURCE_PREFIX + source.id)
        .setUri(audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(source.name)
                .setArtist(episodeTitle)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .build()
        )
        .build()
