package com.yourname.pdftoolkit.review

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Open Source flavor: Manages the review flow without any Google Play Services.
 * Redirects to GitHub releases page for user feedback.
 */
class ReviewManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val reviewPreferences = ReviewPreferences.getInstance(context)
    private val usageTracker = UsageTracker.getInstance(context)

    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Check if review should be triggered based on all conditions:
     * - Feature used >= MIN_FEATURE_USAGE times
     * - Session time >= MIN_SESSION_TIME_MS
     * - Last prompt > DAYS_BETWEEN_PROMPTS days ago
     * - User has not rated
     *
     * @return true if review should be shown
     */
    suspend fun shouldRequestReview(): Boolean = withContext(Dispatchers.IO) {
        val data = reviewPreferences.getReviewData()

        // If user has already rated, never show again
        if (data.hasRated) {
            ReviewLogger.logAlreadyRated()
            return@withContext false
        }

        val currentTime = System.currentTimeMillis()

        // Check cooldown period
        if (data.lastReviewPromptTimestamp > 0) {
            val daysSinceLastPrompt = (currentTime - data.lastReviewPromptTimestamp) / (1000 * 60 * 60 * 24)
            if (daysSinceLastPrompt < DAYS_BETWEEN_PROMPTS) {
                ReviewLogger.logCooldown(DAYS_BETWEEN_PROMPTS - daysSinceLastPrompt)
                return@withContext false
            }
        }

        // Get current total feature usage across all features
        val totalUsage = data.getTotalFeatureUsage()

        // Get current total session time
        val sessionTimeMs = usageTracker.getCurrentTotalSessionTimeMs()

        // Check if thresholds are met
        val hasEnoughUsage = totalUsage >= MIN_FEATURE_USAGE
        val hasEnoughSessionTime = sessionTimeMs >= MIN_SESSION_TIME_MS

        // Log the check
        val shouldTrigger = hasEnoughUsage && hasEnoughSessionTime
        ReviewLogger.logTriggerCheck(totalUsage, sessionTimeMs, data.lastReviewPromptTimestamp, data.hasRated, shouldTrigger)

        // Log specific thresholds not met
        if (!hasEnoughUsage) {
            ReviewLogger.logThresholdNotMet("Feature usage: $totalUsage < $MIN_FEATURE_USAGE")
        }
        if (!hasEnoughSessionTime) {
            ReviewLogger.logThresholdNotMet("Session time: ${sessionTimeMs / 1000}s < ${MIN_SESSION_TIME_MS / 1000}s")
        }

        shouldTrigger
    }

    /**
     * Request a review flow. Call this when the user completes a meaningful action.
     * On Open Source flavor, this redirects to GitHub releases page.
     *
     * @param activity The current activity for showing the review dialog
     * @param onComplete Called when the review flow completes (success or failure)
     */
    fun requestReview(activity: Activity, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                if (!shouldRequestReview()) {
                    onComplete(false)
                    return@launch
                }

                // Mark that we're showing the prompt (prevents spam)
                reviewPreferences.markReviewPromptShown()

                // Open Source: Redirect to GitHub releases page
                redirectToGitHubReleases(activity)
                markAsRated()
                onComplete(true)
            } catch (e: Exception) {
                ReviewLogger.logError("Error in requestReview", e)
                onComplete(false)
            }
        }
    }

    /**
     * Request review with suspend function for coroutine usage
     *
     * @param activity The current activity
     * @return true if review was shown or redirected, false otherwise
     */
    suspend fun requestReviewSuspend(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        try {
            if (!shouldRequestReview()) {
                return@withContext false
            }

            // Mark that we're showing the prompt
            reviewPreferences.markReviewPromptShown()

            // Open Source: Redirect to GitHub releases page
            redirectToGitHubReleases(activity)
            markAsRated()
            true
        } catch (e: Exception) {
            ReviewLogger.logError("Error in requestReviewSuspend", e)
            false
        }
    }

    /**
     * Redirect user to GitHub releases page for feedback
     *
     * @param activity The current activity
     */
    private fun redirectToGitHubReleases(activity: Activity) {
        ReviewLogger.logPlayStoreRedirect()

        // Open Source: Use GitHub releases page (no Play Store)
        val githubUri = Uri.parse("https://github.com/Karna14314/pdf-toolkit/releases")
        val githubIntent = Intent(Intent.ACTION_VIEW, githubUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }

        try {
            activity.startActivity(githubIntent)
        } catch (e: ActivityNotFoundException) {
            ReviewLogger.logError("No browser available to open GitHub", e)
        }
    }

    /**
     * Mark user as rated to permanently suppress review prompts
     */
    private suspend fun markAsRated() {
        reviewPreferences.markAsRated()
        usageTracker.resetSessionTime()
        ReviewLogger.logUserMarkedAsRated()
    }

    /**
     * Manually mark user as rated (e.g., from settings screen)
     */
    suspend fun markUserAsRated() {
        markAsRated()
    }

    /**
     * Check if user has already rated
     */
    suspend fun hasUserRated(): Boolean {
        return reviewPreferences.getReviewData().hasRated
    }

    /**
     * Get current review statistics (for debugging/analytics)
     */
    suspend fun getReviewStats(): ReviewStats = withContext(Dispatchers.IO) {
        val data = reviewPreferences.getReviewData()
        val sessionTime = usageTracker.getCurrentTotalSessionTimeMs()

        ReviewStats(
            pdfViewerUsage = data.pdfViewerUsage,
            mergeUsage = data.mergeUsage,
            splitUsage = data.splitUsage,
            lockUsage = data.lockUsage,
            compressUsage = data.compressUsage,
            totalFeatureUsage = data.getTotalFeatureUsage(),
            totalSessionTimeMs = sessionTime,
            totalSessionTimeMinutes = sessionTime / (1000 * 60),
            lastReviewPromptTimestamp = data.lastReviewPromptTimestamp,
            hasRated = data.hasRated,
            canRequestReview = !data.hasRated &&
                data.getTotalFeatureUsage() >= MIN_FEATURE_USAGE &&
                sessionTime >= MIN_SESSION_TIME_MS
        )
    }

    /**
     * Data class containing review statistics
     */
    data class ReviewStats(
        val pdfViewerUsage: Int,
        val mergeUsage: Int,
        val splitUsage: Int,
        val lockUsage: Int,
        val compressUsage: Int,
        val totalFeatureUsage: Int,
        val totalSessionTimeMs: Long,
        val totalSessionTimeMinutes: Long,
        val lastReviewPromptTimestamp: Long,
        val hasRated: Boolean,
        val canRequestReview: Boolean
    )

    companion object {
        // Trigger thresholds
        const val MIN_FEATURE_USAGE = 3
        const val MIN_SESSION_TIME_MS = 10 * 60 * 1000L // 10 minutes
        const val DAYS_BETWEEN_PROMPTS = 3L

        @Volatile
        private var instance: ReviewManager? = null

        fun getInstance(context: Context): ReviewManager {
            return instance ?: synchronized(this) {
                instance ?: ReviewManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * For testing - resets the singleton instance
         */
        internal fun resetInstance() {
            instance = null
        }
    }
}
