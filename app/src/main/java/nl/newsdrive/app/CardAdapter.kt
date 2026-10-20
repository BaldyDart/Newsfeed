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
            val side = (56 * pager.resources.displayMetrics.density).toInt()
            pager.setPadding(side, 0, side, 0)
            pager.offscreenPageLimit = 3
            pager.setPageTransformer { page, position ->
                val p = position.coerceIn(-2f, 2f)
                val a = abs(p)
                page.cameraDistance = 12000f * page.resources.displayMetrics.density
                page.rotationY = -38f * p
                page.scaleX = 1f - 0.16f * minOf(a, 1f)
                page.scaleY = page.scaleX
                page.translationX = -p * page.width * 0.06f
                page.alpha = 1f - 0.45f * minOf(a, 1f)
                page.translationZ = -a
            }
        }
    }
}
