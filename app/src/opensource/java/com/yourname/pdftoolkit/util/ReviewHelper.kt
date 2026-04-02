package com.yourname.pdftoolkit.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Helper class to handle Reviews.
 * Open Source implementation shows a custom dialog linking to GitHub.
 */
object ReviewHelper {

    private const val TAG = "ReviewHelper"
    private const val GITHUB_URL = "https://github.com/Karna14314/pdf-toolkit"
    private const val GITHUB_RELEASES_URL = "https://github.com/Karna14314/pdf-toolkit/releases"

    /**
     * Trigger the review flow (Custom Dialog for Open Source).
     *
     * @param activity The activity context.
     */
    fun showReview(activity: Activity) {
        try {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Enjoying PDF Toolkit?")
            builder.setMessage("If you like this app, please consider starring us on GitHub or checking out the latest releases. Your support helps us keep it free and open source!")

            builder.setPositiveButton("Star on GitHub") { dialog, _ ->
                openUrl(activity, GITHUB_URL)
                dialog.dismiss()
            }

            builder.setNegativeButton("Maybe Later") { dialog, _ ->
                dialog.dismiss()
            }

            builder.setNeutralButton("View Releases") { dialog, _ ->
                openUrl(activity, GITHUB_RELEASES_URL)
                dialog.dismiss()
            }

            builder.show()
            Log.d(TAG, "Custom review dialog shown")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing review dialog", e)
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URL", e)
        }
    }
}
