package com.yourname.pdftoolkit.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart

/**
 * Theme modes available in the app.
 */
enum class ThemeMode(val value: Int, val displayName: String) {
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO, "Light"),
    DARK(AppCompatDelegate.MODE_NIGHT_YES, "Dark"),
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "System Default");
    
    companion object {
        fun fromValue(value: Int): ThemeMode {
            return entries.find { it.value == value } ?: SYSTEM
        }
    }
}

/**
 * ThemeManager handles theme persistence and application using SharedPreferences.
 * Provides a synchronous way to read theme on startup and reactive flow for UI updates.
 */
object ThemeManager {
    
    private const val PREFS_NAME = "theme_preferences"
    private const val KEY_THEME_MODE = "theme_mode"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get the current theme mode as a Flow.
     * Defaults to SYSTEM if no preference is set.
     */
    fun getThemeMode(context: Context): Flow<ThemeMode> = callbackFlow {
        val prefs = getPrefs(context)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == KEY_THEME_MODE) {
                val modeValue = sharedPreferences.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.value)
                trySend(ThemeMode.fromValue(modeValue))
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        // Initial emission handled by onStart to avoid race conditions

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.onStart {
        // Emit current value immediately
        emit(getThemeModeImmediate(context))
    }

    /**
     * Get the theme mode synchronously.
     * Suitable for app startup initialization.
     */
    fun getThemeModeImmediate(context: Context): ThemeMode {
        val modeValue = getPrefs(context).getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.value)
        return ThemeMode.fromValue(modeValue)
    }
    
    /**
     * Save the theme mode preference.
     * 
     * @param context Application context
     * @param themeMode The theme mode to save
     */
    suspend fun setThemeMode(context: Context, themeMode: ThemeMode) {
        // We can use apply() for async save but still update UI immediately
        getPrefs(context).edit().putInt(KEY_THEME_MODE, themeMode.value).apply()
        applyTheme(themeMode)
    }
    
    /**
     * Apply the theme immediately using AppCompatDelegate.
     * 
     * @param themeMode The theme mode to apply
     */
    fun applyTheme(themeMode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(themeMode.value)
    }
    
    /**
     * Initialize theme on app startup.
     * Should be called from Application.onCreate() or MainActivity.onCreate().
     * 
     * @param context Application context
     * @param onThemeLoaded Callback invoked when theme is loaded and applied
     */
    suspend fun initializeTheme(context: Context, onThemeLoaded: ((ThemeMode) -> Unit)? = null) {
        val themeMode = getThemeModeImmediate(context)
        applyTheme(themeMode)
        onThemeLoaded?.invoke(themeMode)

        // Also subscribe to changes if needed (though typically this function was used as a one-shot or ongoing subscription)
        // Given the signature is suspend and typically used with collect, we might want to mirror old behavior if possible,
        // but for startup optimization, we just want immediate application.
        // The previous implementation was: getThemeMode(context).collect { ... }
        // To maintain compatibility if this is called as a long-running coroutine:
        getThemeMode(context).collect { mode ->
            applyTheme(mode)
            onThemeLoaded?.invoke(mode)
        }
    }
}
