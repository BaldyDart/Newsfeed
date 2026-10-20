package nl.newsdrive.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * @param description        one line shown on the Feeds settings screen.
 * @param artwork            default image (URL or android.resource:// URI) used in browse lists and as fallback.
 * @param preferFeedArtwork  true = use the image the feed itself publishes when available.
 */
data class NewsSource(
    val id: String,
    val name: String,
    val feedUrl: String,
    val description: String = "",
    val artwork: String? = null,
    val preferFeedArtwork: Boolean = true,
    /** Set for live radio streams: played directly, for [liveMinutes], then the queue moves on. */
    val streamUrl: String? = null,
    val liveMinutes: Int = 5,
    /** Whether the feed is switched on until the user changes it on the Feeds screen. */
    val defaultOn: Boolean = true,
) {
    val isLive: Boolean get() = streamUrl != null
}

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
            description = "Dutch radio news from ANP, every hour",
            feedUrl = "https://www.omnycontent.com/d/playlist/56ccbbb7-0ff7-4482-9d99-a88800f49f6c/" +
                "25c8f040-d23c-4776-b211-a8e3009d004d/6ec59e7c-895f-4ed5-8c28-a8e3009d51ca/podcast.rss",
            artwork = RES + "tile_anp",
            preferFeedArtwork = false,
        ),
        // BNR Nieuwsupdate (every 30 minutes).
        NewsSource(
            id = "bnr",
            name = "BNR Nieuwsupdate",
            description = "Dutch business news radio, every 30 minutes",
            feedUrl = "https://www.omnycontent.com/d/playlist/8257a063-6be9-42fa-b892-acd4013b1255/" +
                "84d4de9f-8ca2-40c5-8d33-b0ce00d6f186/e4c79cb6-51a3-4c08-9573-b0ce00d746db/podcast.rss",
            artwork = "https://www.omnycontent.com/d/programs/8257a063-6be9-42fa-b892-acd4013b1255/" +
                "84d4de9f-8ca2-40c5-8d33-b0ce00d6f186/image.jpg?t=1701704083&size=Large",
        ),
        // NPR News Now (hourly, ~5 minutes).
        NewsSource(
            id = "npr",
            name = "NPR News Now",
            description = "English news from NPR, every hour",
            feedUrl = "https://feeds.npr.org/500005/podcast.xml",
            artwork = "https://media.npr.org/assets/img/2023/01/24/" +
                "npr-news-now_tile_sq-2334a5fd44563fa94cdcdb27384b0f05987c2d15.jpg?s=1400&c=66&f=jpg",
        ),
        // VRT NWS (Flemish public broadcaster): live radio stream, played for a few minutes.
        // Live radio has no "latest bulletin", so it plays what is on air and then moves on.
        NewsSource(
            id = "vrt",
            name = "VRT NWS",
            description = "Flemish news radio, live stream (plays 5 minutes)",
            feedUrl = "",
            streamUrl = "https://icecast.vrtcdn.be/vrtnws-high.mp3",
            liveMinutes = 5,
            artwork = RES + "tile_vrt",
        ),
        // BBC Minute: one-minute world news bulletin, updated every hour.
        NewsSource(
            id = "bbc",
            name = "BBC World News",
            description = "BBC Minute, one-minute world news every hour",
            feedUrl = "https://podcasts.files.bbci.co.uk/p03q9cgl.rss",
            artwork = RES + "tile_bbc",
            preferFeedArtwork = false,
        ),
        // Longer BBC World Service programme, several times a day.
        NewsSource(
            id = "bbcgn",
            name = "BBC Global News",
            description = "BBC Global News Podcast, about 30 min (off by default)",
            feedUrl = "https://podcasts.files.bbci.co.uk/p02nq0gn.rss",
            artwork = RES + "tile_bbc",
            preferFeedArtwork = false,
            defaultOn = false,
        ),
        // Limburg regional radio (L1). A live stream, not a news-only feed: it plays what is on air.
        NewsSource(
            id = "l1",
            name = "L1 Limburg",
            description = "Regional radio for Dutch Limburg, live (plays 5 min, off by default)",
            feedUrl = "",
            streamUrl = "https://d34pj260kw1xmk.cloudfront.net/icecast/l1/radio-bb-mp3",
            liveMinutes = 5,
            artwork = RES + "tile_l1",
            defaultOn = false,
        ),
    )

    fun byId(id: String): NewsSource? = SOURCES.firstOrNull { it.id == id }
}

object FeedFetcher {

    /** Downloads just enough of the feed to find the newest playable episode. Returns null on failure. */
    fun latest(source: NewsSource): Bulletin? {
        source.streamUrl?.let {
            return Bulletin(source, "Live radio · ${source.liveMinutes} min", it, source.artwork)
        }
        var conn = open(source.feedUrl)
        return try {
            // Follow redirects by hand, because HttpURLConnection will not switch between http and https.
            var hops = 0
            while (conn.responseCode in 300..399 && hops < 5) {
                val next = conn.getHeaderField("Location") ?: return null
                val target = URL(URL(conn.url.toString()), next).toString()
                conn.disconnect()
                conn = open(target)
                hops++
            }
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

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "NewsNow/1.4 (Android)")
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
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
