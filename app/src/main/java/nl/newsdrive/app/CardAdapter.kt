package nl.newsdrive.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/** One card per bulletin / station. A null url shows the app tile. */
class CardAdapter : RecyclerView.Adapter<CardAdapter.Holder>() {

    private var urls: List<String?> = emptyList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.cardImage)
    }

    /** Returns true when the list changed. */
    fun submit(newUrls: List<String?>): Boolean {
        if (newUrls == urls) return false
        urls = newUrls
        notifyDataSetChanged()
        return true
    }

    override fun getItemCount() = urls.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val url = urls[position] ?: NewsConfig.APP_TILE
        holder.image.tag = null
        ArtworkLoader.load(holder.image.context, url, holder.image)
    }

    companion object {
        /** Cover-flow look: side cards turn away, shrink and fade. */
        fun install(pager: ViewPager2) {
            val d = pager.resources.displayMetrics.density
            val side = (84 * d).toInt()
            pager.setPadding(side, 0, side, 0)
            pager.offscreenPageLimit = 3
            pager.setPageTransformer { page, position ->
                val p = position.coerceIn(-2f, 2f)
                val a = abs(p)
                val near = minOf(a, 1f)
                page.cameraDistance = 9000f * d
                page.rotationY = -48f * p
                page.scaleX = 1f - 0.2f * near - 0.08f * (a - near)
                page.scaleY = page.scaleX
                // Pull the side cards under the middle one so they stack behind it.
                page.translationX = -p * page.width * 0.34f
                page.alpha = 1f - 0.35f * near - 0.3f * (a - near)
                page.translationZ = 24f * d * (1f - near)
            }
        }
    }
}
