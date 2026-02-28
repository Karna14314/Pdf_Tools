package com.yourname.pdftoolkit.util

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Helper class to handle In-App Reviews.
 * Play Store implementation uses the official Google Play In-App Review API.
 */
object ReviewHelper {

    private const val TAG = "ReviewHelper"

    /**
     * Trigger the In-App Review flow.
     *
     * @param activity The activity context required to launch the review flow.
     */
    fun showReview(activity: Activity) {
        val manager = ReviewManagerFactory.create(activity)
        val request = manager.requestReviewFlow()

        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // We got the ReviewInfo object
                val reviewInfo = task.result
                Log.d(TAG, "ReviewInfo request successful, launching review flow")

                val flow = manager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener { _ ->
                    // The flow has finished. The API does not indicate whether the user
                    // reviewed or not, or even whether the review dialog was shown.
                    // Thus, no matter the result, we continue our app flow.
                    Log.d(TAG, "Review flow completed")
                }
            } else {
                // There was some problem, log or handle the error code.
                Log.e(TAG, "ReviewInfo request failed", task.exception)
            }
        }
    }
}
