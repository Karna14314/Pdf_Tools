package com.yourname.pdftoolkit.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Glide-based image loading utilities with automatic EXIF rotation,
 * lazy loading, and smart caching.
 * 
 * Features:
 * - Lazy loading with proper placeholder/error handling
 * - Auto-clear cache after operations
 * - Proper EXIF rotation handling
 * - Memory-efficient thumbnail loading
 * - No duplicate bitmap copies in memory
 */
object ImageViewerUtils {
    
    // Default thumbnail size for previews
    private const val THUMBNAIL_SIZE = 256
    
    // Maximum memory cache size percentage
    private const val MEMORY_CACHE_SCREEN_FACTOR = 0.25f
    
    /**
     * Load image into ImageView with lazy loading and caching.
     * Automatically handles EXIF rotation.
     *
     * @param context Android context
     * @param imageView Target ImageView
     * @param uri Image URI
     * @param placeholder Placeholder resource ID
     * @param errorDrawable Error resource ID
     * @param cornerRadius Corner radius for rounded corners (0 for none)
     * @param thumbnail Whether to load as thumbnail (faster, lower quality)
     */
    fun loadImage(
        context: Context,
        imageView: ImageView,
        uri: Uri,
        placeholder: Int? = null,
        errorDrawable: Int? = null,
        cornerRadius: Int = 0,
        thumbnail: Boolean = false
    ) {
        val requestBuilder = Glide.with(context)
            .load(uri)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        
        val requestOptions = RequestOptions().apply {
            if (thumbnail) {
                override(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
            }
            if (cornerRadius > 0) {
                transform(CenterCrop(), RoundedCorners(cornerRadius))
            }
            placeholder?.let { placeholder(it) }
            errorDrawable?.let { error(it) }
        }
        
        requestBuilder
            .apply(requestOptions)
            .into(imageView)
    }
    
    /**
     * Load image into ImageView from file path.
     */
    fun loadImage(
        context: Context,
        imageView: ImageView,
        filePath: String,
        placeholder: Int? = null,
        errorDrawable: Int? = null,
        cornerRadius: Int = 0,
        thumbnail: Boolean = false
    ) {
        loadImage(
            context = context,
            imageView = imageView,
            uri = Uri.fromFile(File(filePath)),
            placeholder = placeholder,
            errorDrawable = errorDrawable,
            cornerRadius = cornerRadius,
            thumbnail = thumbnail
        )
    }
    
    /**
     * Load image as Bitmap asynchronously.
     * Use this for processing operations.
     *
     * @param context Android context
     * @param uri Image URI
     * @param maxSize Maximum dimension (for memory safety)
     * @return Bitmap or null on failure
     */
    suspend fun loadBitmap(
        context: Context,
        uri: Uri,
        maxSize: Int = 2048
    ): Bitmap? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            Glide.with(context)
                .asBitmap()
                .load(uri)
                .apply(RequestOptions().override(maxSize, maxSize))
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(resource)
                        }
                    }
                    
                    override fun onLoadCleared(placeholder: Drawable?) {
                        // Bitmap will be recycled by Glide
                    }
                    
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                })
            
            continuation.invokeOnCancellation {
                // Glide handles cancellation automatically
            }
        }
    }
    
    /**
     * Preload images for smoother scrolling.
     * Useful before displaying a gallery or list.
     *
     * @param context Android context
     * @param uris List of image URIs to preload
     * @param asThumbnails Whether to preload as thumbnails
     */
    fun preloadImages(
        context: Context,
        uris: List<Uri>,
        asThumbnails: Boolean = true
    ) {
        val size = if (asThumbnails) THUMBNAIL_SIZE else Target.SIZE_ORIGINAL
        
        uris.forEach { uri ->
            Glide.with(context)
                .load(uri)
                .apply(RequestOptions().override(size, size))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .preload()
        }
    }
    
    /**
     * Clear memory cache.
     * Call this when memory pressure is detected.
     */
    fun clearMemoryCache(context: Context) {
        Glide.get(context).clearMemory()
    }
    
    /**
     * Clear disk cache.
     * Must be called on background thread.
     */
    suspend fun clearDiskCache(context: Context) = withContext(Dispatchers.IO) {
        Glide.get(context).clearDiskCache()
    }
    
    /**
     * Clear all Glide caches.
     */
    suspend fun clearAllCaches(context: Context) {
        clearMemoryCache(context)
        clearDiskCache(context)
    }
    
    /**
     * Pause all active loads.
     * Useful when scrolling fast or during heavy operations.
     */
    fun pauseRequests(context: Context) {
        Glide.with(context).pauseRequests()
    }
    
    /**
     * Resume paused loads.
     */
    fun resumeRequests(context: Context) {
        Glide.with(context).resumeRequests()
    }
    
    /**
     * Get thumbnail bitmap synchronously.
     * Use sparingly - prefer async loading.
     */
    suspend fun getThumbnail(
        context: Context,
        uri: Uri,
        size: Int = THUMBNAIL_SIZE
    ): Bitmap? = loadBitmap(context, uri, size)
    
    /**
     * Load image with callback for completion.
     */
    fun loadImageWithCallback(
        context: Context,
        imageView: ImageView,
        uri: Uri,
        onSuccess: (() -> Unit)? = null,
        onError: ((Exception?) -> Unit)? = null
    ) {
        Glide.with(context)
            .load(uri)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    onError?.invoke(e)
                    return false
                }
                
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    onSuccess?.invoke()
                    return false
                }
            })
            .into(imageView)
    }
    
    /**
     * Cancel all pending loads for a view.
     */
    fun cancelLoad(context: Context, imageView: ImageView) {
        Glide.with(context).clear(imageView)
    }
    
    /**
     * Get the Glide instance for advanced configuration.
     */
    fun getGlide(context: Context) = Glide.get(context)
}
