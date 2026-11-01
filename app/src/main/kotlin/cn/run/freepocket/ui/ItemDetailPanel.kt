package cn.run.freepocket.ui

import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import cn.run.freepocket.R
import cn.run.freepocket.databinding.NativeArtworkBinding
import cn.run.freepocket.databinding.NativePanelBinding
import cn.run.freepocket.model.CatalogItem

internal class ItemDetailPanel(
    private val binding: NativePanelBinding,
    private val images: NativeImageLoader,
    private val onAction: (PocketAction) -> Unit
) {
    private var rendered: CatalogItem? = null
    private var officialButton: android.widget.Button? = null
    private var deadline: android.widget.TextView? = null
    private val artwork = mutableListOf<NativeArtworkBinding>()

    fun render(item: CatalogItem, busy: Boolean, now: Long) {
        officialButton?.isEnabled = !busy
        deadline?.text = item.deadlineLabel(binding.root.context, now)
        if (rendered == item) return
        clearImages()
        rendered = item
        val context = binding.root.context
        val inflater = LayoutInflater.from(context)
        binding.title.text = item.title
        binding.body.removeAllViews()
        with(binding.body) {
            val cover = NativeArtworkBinding.inflate(inflater, this, false)
            addView(cover.root)
            artwork.add(cover)
            images.bind(cover.image, cover.imageStatus, item.cover, context.getString(R.string.native_cover, item.title), false)
            addText(item.ownershipLabel(context), heading = true)
            deadline = addText(item.deadlineLabel(context, now))
            if (item.originalPrice.isNotBlank()) addText(context.getString(R.string.native_original_price, item.originalPrice))
            if (item.checkedAt != null) addText(context.getString(
                if (item.cached) R.string.native_last_sync else R.string.native_checked, localTime(item.checkedAt)))
            if (item.statusReason.isNotBlank()) addText(item.statusReason)
            if (item.publisher.isNotBlank()) addText(context.getString(R.string.native_publisher, item.publisher))
            officialButton = addAction(context.getString(R.string.native_official), !busy, primary = true) { onAction(PocketAction.OpenOfficial(item.key)) }
            if (item.screenshots.isNotEmpty()) {
                addText(context.getString(R.string.native_screenshots), heading = true)
                val gallery = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val scroller = HorizontalScrollView(context).apply { isFillViewport = false; addView(gallery) }
                addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
                item.screenshots.forEachIndexed { index, url ->
                    val shot = NativeArtworkBinding.inflate(inflater, gallery, false)
                    gallery.addView(shot.root, LinearLayout.LayoutParams(context.dp(280), context.dp(180)).apply { marginEnd = context.dp(8) })
                    artwork.add(shot)
                    images.bind(shot.image, shot.imageStatus, url, context.getString(R.string.native_screenshot, item.title, index + 1), false)
                }
            }
            addText(item.description.ifBlank { context.getString(R.string.native_description_missing) })
        }
    }

    private fun clearImages() { artwork.forEach { images.unbind(it.image) }; artwork.clear() }
    fun close() { clearImages() }
}
