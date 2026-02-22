package com.yourname.pdftoolkit.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Manages the "Rate Our App" feature.
 * Tracks usage of PDF tools and triggers a rating prompt after 4 successful uses.
 */
object RatingManager {

    private const val PREFS_NAME = "rating_prefs"
    private const val KEY_USAGE_COUNT = "tool_usage_count"
    private const val KEY_RATING_SHOWN = "rating_shown"
    private const val TRIGGER_COUNT = 4

    private val _showRatingRequest = MutableSharedFlow<Boolean>()
    val showRatingRequest: SharedFlow<Boolean> = _showRatingRequest.asSharedFlow()

    /**
     * Increment usage count. Call this after a successful tool operation.
     * If the count reaches the trigger and rating hasn't been shown, emits an event.
     *
     * @return true if rating should be shown now
     */
    suspend fun incrementUsage(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_RATING_SHOWN, false)) {
            return false
        }

        val currentCount = prefs.getInt(KEY_USAGE_COUNT, 0) + 1
        prefs.edit().putInt(KEY_USAGE_COUNT, currentCount).apply()

        if (currentCount == TRIGGER_COUNT) {
            _showRatingRequest.emit(true)
            // Mark as shown so we don't spam the user, unless we want to retry later?
            // Usually In-App Review API handles quota, but for our custom logic (F-Droid),
            // we should probably not show it again immediately.
            // Let's mark it as shown for now.
            prefs.edit().putBoolean(KEY_RATING_SHOWN, true).apply()
            return true
        }

        return false
    }

    /**
     * Reset usage count (for testing or debugging).
     */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
