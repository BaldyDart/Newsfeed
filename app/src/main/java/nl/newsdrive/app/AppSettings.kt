package nl.newsdrive.app

import android.content.Context
import android.content.SharedPreferences

/** All app options, stored on the device. */
object AppSettings {

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun prefs(c: Context) = p(c)

    // ---- options
    fun skipHeard(c: Context) = p(c).getBoolean("skip_heard", true)
    fun setSkipHeard(c: Context, v: Boolean) = p(c).edit().putBoolean("skip_heard", v).apply()

    fun announce(c: Context) = p(c).getBoolean("announce", false)
    fun setAnnounce(c: Context, v: Boolean) = p(c).edit().putBoolean("announce", v).apply()

    const val KEY_SPEED = "speed"
    val SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    fun speed(c: Context) = p(c).getFloat(KEY_SPEED, 1.0f)
    fun setSpeed(c: Context, v: Float) = p(c).edit().putFloat(KEY_SPEED, v).apply()
    fun speedLabel(v: Float) = (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + "×"

    // ---- auto-start
    fun autoStart(c: Context) = p(c).getBoolean("auto_start", false)
    fun setAutoStart(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_start", v).apply()

    fun btAddress(c: Context): String? = p(c).getString("bt_address", null)
    fun btName(c: Context): String? = p(c).getString("bt_name", null)
    fun setBtDevice(c: Context, address: String?, name: String?) =
        p(c).edit().putString("bt_address", address).putString("bt_name", name).apply()

    // ---- live stream length
    val LIVE_MINUTES = intArrayOf(2, 5, 10, 15)
    fun liveMinutes(c: Context, s: NewsSource): Int = p(c).getInt("minutes_${s.id}", s.liveMinutes)
    fun setLiveMinutes(c: Context, id: String, m: Int) = p(c).edit().putInt("minutes_$id", m).apply()

    // ---- order of feeds
    fun ordered(c: Context): List<NewsSource> {
        val saved = p(c).getString("order", "").orEmpty().split(",").filter { it.isNotEmpty() }
        val all = NewsConfig.sources
        return all.withIndex()
            .sortedBy { (pos, s) -> saved.indexOf(s.id).let { if (it >= 0) it else 1000 + pos } }
            .map { it.value }
    }

    fun saveOrder(c: Context, ids: List<String>) = p(c).edit().putString("order", ids.joinToString(",")).apply()

    // ---- heard bulletins
    fun isHeard(c: Context, b: Bulletin) = p(c).getString("heard_${b.source.id}", null) == b.key
    fun markHeard(c: Context, id: String, key: String) = p(c).edit().putString("heard_$id", key).apply()
}
