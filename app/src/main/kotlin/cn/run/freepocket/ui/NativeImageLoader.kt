package cn.run.freepocket.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.widget.Button
import android.widget.ImageView
import cn.run.freepocket.OfficialImages
import cn.run.freepocket.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.WeakHashMap

internal class NativeImageLoader(context: Context) {
    private val images = OfficialImages(File(context.cacheDir, "official-images"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = WeakHashMap<ImageView, Job>()
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun bind(image: ImageView, status: Button, url: String, description: String, cover: Boolean = true) {
        jobs.remove(image)?.cancel()
        val binding = Any()
        image.tag = binding
        image.setImageDrawable(null)
        image.contentDescription = description
        status.visibility = View.VISIBLE
        status.text = image.context.getString(if (url.isBlank()) R.string.native_image_missing else R.string.native_image_loading)
        status.isEnabled = false
        status.setOnClickListener(null)
        if (url.isBlank()) return
        val registered = images.register(url, cover)
        if (registered.isEmpty()) {
            status.setText(R.string.native_image_retry)
            status.isEnabled = true
            status.setOnClickListener { bind(image, status, url, description, cover) }
            return
        }
        val key = Uri.parse(registered).lastPathSegment ?: return
        memory.get(key)?.let { bitmap -> image.setImageBitmap(bitmap); status.visibility = View.GONE; return }
        jobs[image] = scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val bytes = images.read(key, cover)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (bounds.outWidth / options.inSampleSize > 1600 || bounds.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Unsupported artwork")
                }
                memory.put(key, bitmap)
                if (image.tag === binding) {
                    image.setImageBitmap(bitmap)
                    status.visibility = View.GONE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (image.tag === binding) {
                    status.setText(R.string.native_image_retry)
                    status.isEnabled = true
                    status.setOnClickListener { bind(image, status, url, description, cover) }
                }
            }
        }
    }

    fun unbind(image: ImageView) {
        jobs.remove(image)?.cancel()
        image.tag = null
        image.setImageDrawable(null)
    }

    fun close() { scope.cancel(); jobs.clear(); memory.evictAll() }
}
