package com.yourname.pdftoolkit.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import java.io.File

/**
 * Helper for uCrop integration - lightweight image cropping.
 * 
 * Supports:
 * - Free crop
 * - Fixed aspect ratios (A4, Letter, Square, etc.)
 * - Custom aspect ratios
 * 
 * Usage:
 * - Call from Image → PDF flow for optional cropping
 * - Call from manual image edit screen
 */
object CropHelper {
    
    /**
     * Aspect ratio presets for cropping.
     */
    enum class CropAspectRatio(val x: Float, val y: Float, val displayName: String) {
        FREE(0f, 0f, "Free"),
        SQUARE(1f, 1f, "Square"),
        RATIO_3_2(3f, 2f, "3:2"),
        RATIO_4_3(4f, 3f, "4:3"),
        RATIO_16_9(16f, 9f, "16:9"),
        A4_PORTRAIT(210f, 297f, "A4 Portrait"),
        A4_LANDSCAPE(297f, 210f, "A4 Landscape"),
        LETTER_PORTRAIT(8.5f, 11f, "Letter Portrait"),
        LETTER_LANDSCAPE(11f, 8.5f, "Letter Landscape")
    }
    
    /**
     * Create a crop intent with specified options.
     * 
     * @param context Android context
     * @param sourceUri Source image URI
     * @param aspectRatio Optional aspect ratio (null for free crop)
     * @param maxWidth Maximum output width
     * @param maxHeight Maximum output height
     * @return UCrop builder configured and ready
     */
    fun createCropIntent(
        context: Context,
        sourceUri: Uri,
        aspectRatio: CropAspectRatio? = null,
        maxWidth: Int = 2048,
        maxHeight: Int = 2048
    ): UCrop {
        val destinationUri = createDestinationUri(context)
        
        val options = UCrop.Options().apply {
            // Use app theme colors
            setToolbarColor(Color.parseColor("#1A1A2E"))
            setStatusBarColor(Color.parseColor("#0F0F1A"))
            setActiveControlsWidgetColor(Color.parseColor("#4A90D9"))
            setToolbarWidgetColor(Color.WHITE)
            
            // Compression settings
            setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            
            // UI settings
            setFreeStyleCropEnabled(aspectRatio == null || aspectRatio == CropAspectRatio.FREE)
            setShowCropFrame(true)
            setShowCropGrid(true)
            setCropGridRowCount(3)
            setCropGridColumnCount(3)
            setHideBottomControls(false)
            
            // Disable what we don't need
            setAllowedGestures(
                UCropActivity.SCALE,
                UCropActivity.ROTATE,
                UCropActivity.ALL
            )
            
            // Max size
            setMaxBitmapSize(maxWidth.coerceAtLeast(maxHeight))
        }
        
        val uCrop = UCrop.of(sourceUri, destinationUri)
            .withOptions(options)
            .withMaxResultSize(maxWidth, maxHeight)
        
        // Set aspect ratio if specified
        if (aspectRatio != null && aspectRatio != CropAspectRatio.FREE) {
            uCrop.withAspectRatio(aspectRatio.x, aspectRatio.y)
        }
        
        return uCrop
    }
    
    /**
     * Get the crop intent for an Activity result launcher.
     */
    fun getCropIntent(
        context: Context,
        sourceUri: Uri,
        aspectRatio: CropAspectRatio? = null,
        maxSize: Int = 2048
    ): Intent {
        return createCropIntent(
            context = context,
            sourceUri = sourceUri,
            aspectRatio = aspectRatio,
            maxWidth = maxSize,
            maxHeight = maxSize
        ).getIntent(context)
    }
    
    /**
     * Start crop activity.
     */
    fun startCrop(
        activity: Activity,
        sourceUri: Uri,
        requestCode: Int,
        aspectRatio: CropAspectRatio? = null,
        maxSize: Int = 2048
    ) {
        createCropIntent(
            context = activity,
            sourceUri = sourceUri,
            aspectRatio = aspectRatio,
            maxWidth = maxSize,
            maxHeight = maxSize
        ).start(activity, requestCode)
    }
    
    /**
     * Parse crop result from activity result.
     * 
     * @param resultCode Activity result code
     * @param data Intent data
     * @return Cropped image URI or null on failure/cancellation
     */
    fun getResultUri(resultCode: Int, data: Intent?): Uri? {
        if (resultCode == Activity.RESULT_OK && data != null) {
            return UCrop.getOutput(data)
        }
        return null
    }
    
    /**
     * Get error from failed crop operation.
     */
    fun getError(data: Intent?): Throwable? {
        return data?.let { UCrop.getError(it) }
    }
    
    /**
     * Check if the result indicates a crop operation was cancelled.
     */
    fun isCancelled(resultCode: Int): Boolean {
        return resultCode == Activity.RESULT_CANCELED
    }
    
    /**
     * Create destination URI for cropped image.
     */
    private fun createDestinationUri(context: Context): Uri {
        val timestamp = System.currentTimeMillis()
        val file = File(context.cacheDir, "cropped_${timestamp}.jpg")
        return Uri.fromFile(file)
    }
    
    /**
     * Clean up cropped images from cache.
     */
    fun cleanupCroppedFiles(context: Context) {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("cropped_") && file.name.endsWith(".jpg")) {
                file.delete()
            }
        }
    }
    
    /**
     * Get the file from a cropped URI.
     */
    fun getFileFromUri(uri: Uri): File? {
        return uri.path?.let { File(it) }
    }
    
    /**
     * Delete a specific cropped file.
     */
    fun deleteCroppedFile(uri: Uri): Boolean {
        return getFileFromUri(uri)?.delete() ?: false
    }
}
