package nl.newsdrive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny image loader for the phone UI: http(s) URLs and android.resource:// URIs, memory-cached. */
object ArtworkLoader {

    private const val TARGET_PX = 640

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(8)

    fun load(context: Context, url: String, target: ImageView) {
        target.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        val app = context.applicationContext
        io.execute {
            val bitmap = try {
                decode(app, url)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) cache.put(url, bitmap)
            main.post {
                // Ignore the result if the view has since been asked for another image.
                if (target.tag == url) {
                    if (bitmap != null) target.setImageBitmap(bitmap)
                    else target.setImageResource(R.drawable.ic_news)
                }
            }
        }
    }

    private fun decode(context: Context, url: String): Bitmap? {
        val bytes = open(context, url)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun open(context: Context, url: String): InputStream? {
        val uri = Uri.parse(url)
        return if (uri.scheme == "http" || uri.scheme == "https") {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "NewsDrive/1.0 (Android)")
            if (conn.responseCode in 200..299) conn.inputStream else null
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }
}
