package nl.newsdrive.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * @param artwork            default image (URL or android.resource:// URI) used in browse lists and as fallback.
 * @param preferFeedArtwork  true = use the image the feed itself publishes when available.
 */
data class NewsSource(
    val id: String,
    val name: String,
    val feedUrl: String,
    val artwork: String? = null,
    val preferFeedArtwork: Boolean = true,
)

/** One resolved bulletin: the newest audio episode of a source. */
data class Bulletin(
    val source: NewsSource,
    val episodeTitle: String,
    val audioUrl: String,
    val artworkUrl: String?,
)

object NewsConfig {
    const val ROOT_ID = "root"
    const val ALL_ID = "all"
    const val SOURCE_PREFIX = "src:"

    const val ACTION_REFRESH = "nl.newsdrive.REFRESH"
    const val ACTION_BACK10 = "nl.newsdrive.BACK10"
    const val ACTION_FWD10 = "nl.newsdrive.FWD10"

    private const val RES = "android.resource://nl.newsdrive.app/raw/"
    const val APP_TILE = RES + "tile_news"

    /**
     * Playback order = list order. To change a station, edit the feedUrl here.
     * Each feed must be a podcast RSS feed whose newest <item> carries an audio <enclosure>.
     */
    val SOURCES = listOf(
        // ANP radio news bulletin as produced for 538 (hourly).
        // The feed's own image is the 538 logo, so a plain "ANP Journaal" tile is used instead.
        NewsSource(
            id = "anp",
            name = "ANP Journaal",
            feedUrl = "https://www.omnycontent.com/d/playlist/56ccbbb7-0ff7-4482-9d99-a88800f49f6c/" +
                "25c8f040-d23c-4776-b211-a8e3009d004d/6ec59e7c-895f-4ed5-8c28-a8e3009d51ca/podcast.rss",
            artwork = RES + "tile_anp",
            preferFeedArtwork = false,
        ),
        // BNR Nieuwsupdate (every 30 minutes).
        NewsSource(
            id = "bnr",
            name = "BNR Nieuwsupdate",
            feedUrl = "https://www.omnycontent.com/d/playlist/8257a063-6be9-42fa-b892-acd4013b1255/" +
                "84d4de9f-8ca2-40c5-8d33-b0ce00d6f186/e4c79cb6-51a3-4c08-9573-b0ce00d746db/podcast.rss",
            artwork = "https://www.omnycontent.com/d/programs/8257a063-6be9-42fa-b892-acd4013b1255/" +
                "84d4de9f-8ca2-40c5-8d33-b0ce00d6f186/image.jpg?t=1701704083&size=Large",
        ),
        // NPR News Now (hourly, ~5 minutes).
        NewsSource(
            id = "npr",
            name = "NPR News Now",
            feedUrl = "https://feeds.npr.org/500005/podcast.xml",
            artwork = "https://media.npr.org/assets/img/2023/01/24/" +
                "npr-news-now_tile_sq-2334a5fd44563fa94cdcdb27384b0f05987c2d15.jpg?s=1400&c=66&f=jpg",
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
        var channelImage: String? = null
        var itemImage: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""
                        audioUrl = ""
                        isTrailer = false
                        itemImage = null
                    }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "itunes:episodeType" -> if (inItem) {
                        isTrailer = parser.nextText().trim().equals("trailer", ignoreCase = true)
                    }
                    "itunes:image" -> {
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        if (href.isNotEmpty()) {
                            if (inItem) itemImage = href
                            else if (channelImage == null) channelImage = href
                        }
                    }
                    "enclosure" -> if (inItem) {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url").orEmpty()
                        if (url.isNotEmpty() && (type.isEmpty() || type.startsWith("audio"))) audioUrl = url
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    if (audioUrl.isNotEmpty() && !isTrailer) {
                        val feedArt = itemImage ?: channelImage
                        val art = if (source.preferFeedArtwork) feedArt ?: source.artwork else source.artwork
                        return Bulletin(source, title.ifEmpty { source.name }, audioUrl, art)
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }
        return null
    }
}

fun Bulletin.toMediaItem(): MediaItem {
    val meta = MediaMetadata.Builder()
        .setTitle(source.name)
        .setArtist(episodeTitle)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
    artworkUrl?.let { meta.setArtworkUri(Uri.parse(it)) }
    return MediaItem.Builder()
        .setMediaId(NewsConfig.SOURCE_PREFIX + source.id)
        .setUri(audioUrl)
        .setMediaMetadata(meta.build())
        .build()
}
