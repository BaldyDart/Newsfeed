package nl.newsdrive.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Keeps the last downloaded bulletin of each podcast feed, for use when there is no connection. */
object OfflineCache {

    private const val MAX_BYTES = 30L * 1024 * 1024

    private fun dir(c: Context) = File(c.filesDir, "offline")
    private fun file(c: Context, id: String) = File(dir(c), "$id.audio")
    private fun meta(c: Context) = c.applicationContext.getSharedPreferences("offline", Context.MODE_PRIVATE)

    fun store(c: Context, b: Bulletin) {
        if (b.source.isLive || !b.audioUrl.startsWith("http")) return
        val id = b.source.id
        val f = file(c, id)
        val m = meta(c)
        if (f.exists() && m.getString("${id}_key", null) == b.key) return
        val conn = FeedFetcher.connect(b.audioUrl) ?: return
        val tmp = File(dir(c), "$id.tmp")
        try {
            dir(c).mkdirs()
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (f.exists()) f.delete()
            if (tmp.renameTo(f)) {
                m.edit()
                    .putString("${id}_key", b.key)
                    .putString("${id}_title", b.episodeTitle)
                    .putString("${id}_art", b.artworkUrl)
                    .apply()
            }
        } catch (e: Exception) {
            tmp.delete()
        } finally {
            conn.disconnect()
        }
    }

    fun get(c: Context, s: NewsSource): Bulletin? {
        if (s.isLive) return null
        val f = file(c, s.id)
        val m = meta(c)
        val key = m.getString("${s.id}_key", null) ?: return null
        if (!f.exists()) return null
        val title = m.getString("${s.id}_title", null) ?: s.name
        return Bulletin(s, "$title · offline", Uri.fromFile(f).toString(), m.getString("${s.id}_art", null) ?: s.artwork, key)
    }

    fun delete(c: Context, id: String) {
        file(c, id).delete()
        meta(c).edit().remove("${id}_key").remove("${id}_title").remove("${id}_art").apply()
    }
}
