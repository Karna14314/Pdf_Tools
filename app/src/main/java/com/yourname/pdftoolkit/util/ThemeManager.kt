package com.yourname.pdftoolkit.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * ThemeManager handles theme persistence and application using DataStore.
 * Provides a modern, type-safe way to manage app theme preferences.
 */
object ThemeManager {
    
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")
    private val THEME_MODE_KEY = intPreferencesKey("theme_mode")
    
    /**
     * Get the current theme mode as a Flow.
     * Defaults to SYSTEM if no preference is set.
     */
    fun getThemeMode(context: Context): Flow<ThemeMode> {
        return context.dataStore.data.map { preferences ->
            val modeValue = preferences[THEME_MODE_KEY] ?: ThemeMode.SYSTEM.value
            ThemeMode.fromValue(modeValue)
        }
    }
    
    /**
     * Save the theme mode preference.
     * 
     * @param context Application context
     * @param themeMode The theme mode to save
     */
    suspend fun setThemeMode(context: Context, themeMode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = themeMode.value
        }
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
        getThemeMode(context).collect { themeMode ->
            applyTheme(themeMode)
            onThemeLoaded?.invoke(themeMode)
        }
    }
}
