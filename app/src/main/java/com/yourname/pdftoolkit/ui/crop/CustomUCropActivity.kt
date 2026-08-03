package com.yourname.pdftoolkit.ui.crop

import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.yalantis.ucrop.UCropActivity

/**
 * Custom UCropActivity that handles WindowInsets (navigation bars & status bars)
 * to prevent bottom controls (crop, rotate, scale) from overlapping with the system navigation bar
 * on Android 15 / Target SDK 35+ and down to Min SDK 26.
 */
class CustomUCropActivity : UCropActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val contentView = findViewById<ViewGroup>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            // Apply bottom inset padding so UCrop's bottom controls stay above 3-button nav / gesture bar
            contentView.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }
    }
}
