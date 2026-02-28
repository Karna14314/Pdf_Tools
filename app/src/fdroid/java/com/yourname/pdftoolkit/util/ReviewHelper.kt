package com.yourname.pdftoolkit.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Helper class to handle Reviews.
 * F-Droid implementation shows a custom dialog since there's no native store review API.
 */
object ReviewHelper {

    private const val TAG = "ReviewHelper"
    private const val GITHUB_URL = "https://github.com/yourname/pdftoolkit" // Replace with actual repo URL if known

    /**
     * Trigger the review flow (Custom Dialog for F-Droid).
     *
     * @param activity The activity context.
     */
    fun showReview(activity: Activity) {
        try {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Enjoying PDF Toolkit?")
            builder.setMessage("If you like this app, please consider starring us on GitHub or sharing it with friends. Your support helps us keep it free and open source!")

            builder.setPositiveButton("Star on GitHub") { dialog, _ ->
                openUrl(activity, GITHUB_URL)
                dialog.dismiss()
            }

            builder.setNegativeButton("Maybe Later") { dialog, _ ->
                dialog.dismiss()
            }

            builder.setNeutralButton("Share App") { dialog, _ ->
                shareApp(activity)
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

    private fun shareApp(activity: Activity) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PDF Toolkit")
                putExtra(Intent.EXTRA_TEXT, "Check out PDF Toolkit, a free and open source PDF tool: $GITHUB_URL")
            }
            activity.startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing app", e)
        }
    }
}
