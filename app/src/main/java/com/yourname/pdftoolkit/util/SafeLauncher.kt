package com.yourname.pdftoolkit.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * Safely launches an [ActivityResultLauncher], catching [ActivityNotFoundException] if the device
 * lacks a compatible Activity/Provider (e.g. missing DocumentsUI / Document Provider on debloated ROMs).
 *
 * @param input Input parameter for the launcher contract.
 * @param context Android [Context] used to display user notification if handler activity is missing.
 * @param onNotFound Optional custom handler for [ActivityNotFoundException]. If null, a Toast is shown.
 */
fun <I> ActivityResultLauncher<I>.safeLaunch(
    input: I,
    context: Context,
    onNotFound: (() -> Unit)? = null
) {
    try {
        launch(input)
    } catch (e: ActivityNotFoundException) {
        if (onNotFound != null) {
            onNotFound()
        } else {
            Toast.makeText(
                context,
                "No compatible document provider or file manager is available on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
