package com.yourname.pdftoolkit.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import java.io.File
import java.io.FileOutputStream


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
 * 
 * NOTE: This helper properly handles content:// URIs by copying them to cache
 * and using FileProvider URIs that uCrop can read/write on Android 10+.
 */
object CropHelper {
    
    private const val TAG = "CropHelper"
    
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
     * Get the crop intent for an Activity result launcher.
     * 
     * IMPORTANT: This copies the source image to cache if it's a content:// URI
     * because uCrop cannot directly access content:// URIs from other apps.
     */
    fun getCropIntent(
        context: Context,
        sourceUri: Uri,
        aspectRatio: CropAspectRatio? = null,
        maxSize: Int = 2048
    ): Intent {
        try {
            // Step 1: Prepare source URI - copy to cache if it's a content:// URI
            val localSourceUri = prepareSourceUri(context, sourceUri)
            
            // Step 2: Create destination file and get FileProvider URI
            val destinationFile = createDestinationFile(context)
            val destinationUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                destinationFile
            )
            
            Log.d(TAG, "Source URI: $localSourceUri")
            Log.d(TAG, "Destination URI: $destinationUri")
            
            // Step 3: Configure uCrop options
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
                
                // Gesture settings
                setAllowedGestures(
                    UCropActivity.SCALE,
                    UCropActivity.ROTATE,
                    UCropActivity.ALL
                )
                
                // Max size
                setMaxBitmapSize(maxSize)
            }
            
            // Step 4: Create uCrop intent
            val uCrop = UCrop.of(localSourceUri, destinationUri)
                .withOptions(options)
                .withMaxResultSize(maxSize, maxSize)
            
            // Set aspect ratio if specified
            if (aspectRatio != null && aspectRatio != CropAspectRatio.FREE) {
                uCrop.withAspectRatio(aspectRatio.x, aspectRatio.y)
            }
            
            // Step 5: Get intent and add URI permissions
            val intent = uCrop.getIntent(context)
            
            // Grant read permission to source and write permission to destination
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            
            return intent
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create crop intent", e)
            throw e
        }
    }
    
    /**
     * Prepare source URI for uCrop.
     * If it's a content:// URI from another app, copy it to local cache first.
     */
    private fun prepareSourceUri(context: Context, sourceUri: Uri): Uri {
        // Check if it's already a file:// URI or our own FileProvider URI
        if (sourceUri.scheme == "file") {
            // Convert to FileProvider URI
            val file = File(sourceUri.path ?: return sourceUri)
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
        }
        
        // Check if it's our own FileProvider
        val authority = sourceUri.authority ?: ""
        if (authority.contains(context.packageName)) {
            // It's our own FileProvider URI, return as-is
            return sourceUri
        }
        
        // It's a content:// URI from another app - copy to cache
        return try {
            val timestamp = System.currentTimeMillis()
            val cacheFile = File(context.cacheDir, "crop_source_${timestamp}.jpg")
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Cannot open source URI")
            
            Log.d(TAG, "Copied source to cache: ${cacheFile.absolutePath}")
            
            // Return FileProvider URI for the cached file
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                cacheFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy source to cache", e)
            // Return original URI as fallback (may fail on uCrop)
            sourceUri
        }
    }
    
    /**
     * Create destination file for cropped image.
     */
    private fun createDestinationFile(context: Context): File {
        val timestamp = System.currentTimeMillis()
        return File(context.cacheDir, "cropped_${timestamp}.jpg")
    }
    
    /**
     * Create a crop intent with specified options.
     * DEPRECATED: Use getCropIntent instead for proper content:// URI handling.
     */
    @Deprecated("Use getCropIntent instead", ReplaceWith("getCropIntent(context, sourceUri, aspectRatio, maxWidth)"))
    fun createCropIntent(
        context: Context,
        sourceUri: Uri,
        aspectRatio: CropAspectRatio? = null,
        maxWidth: Int = 2048,
        maxHeight: Int = 2048
    ): UCrop {
        val localSourceUri = prepareSourceUri(context, sourceUri)
        val destinationFile = createDestinationFile(context)
        val destinationUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            destinationFile
        )
        
        val options = UCrop.Options().apply {
            setToolbarColor(Color.parseColor("#1A1A2E"))
            setStatusBarColor(Color.parseColor("#0F0F1A"))
            setActiveControlsWidgetColor(Color.parseColor("#4A90D9"))
            setToolbarWidgetColor(Color.WHITE)
            setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setFreeStyleCropEnabled(aspectRatio == null || aspectRatio == CropAspectRatio.FREE)
            setShowCropFrame(true)
            setShowCropGrid(true)
            setCropGridRowCount(3)
            setCropGridColumnCount(3)
            setHideBottomControls(false)
            setAllowedGestures(
                UCropActivity.SCALE,
                UCropActivity.ROTATE,
                UCropActivity.ALL
            )
            setMaxBitmapSize(maxWidth.coerceAtLeast(maxHeight))
        }
        
        val uCrop = UCrop.of(localSourceUri, destinationUri)
            .withOptions(options)
            .withMaxResultSize(maxWidth, maxHeight)
        
        if (aspectRatio != null && aspectRatio != CropAspectRatio.FREE) {
            uCrop.withAspectRatio(aspectRatio.x, aspectRatio.y)
        }
        
        return uCrop
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
        val intent = getCropIntent(
            context = activity,
            sourceUri = sourceUri,
            aspectRatio = aspectRatio,
            maxSize = maxSize
        )
        activity.startActivityForResult(intent, requestCode)
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
     * Clean up cropped images from cache.
     */
    fun cleanupCroppedFiles(context: Context) {
        context.cacheDir.listFiles()?.forEach { file ->
            if ((file.name.startsWith("cropped_") || file.name.startsWith("crop_source_")) 
                && file.name.endsWith(".jpg")) {
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

