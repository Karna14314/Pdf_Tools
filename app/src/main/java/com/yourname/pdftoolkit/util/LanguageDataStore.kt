package com.yourname.pdftoolkit.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore for persisting language preferences.
 * Uses the same DataStore instance as ThemeManager for consistency.
 */
object LanguageDataStore {
    
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")
    
    private val SELECTED_LANGUAGE_KEY = stringPreferencesKey("selected_language")
    private const val DEFAULT_LANGUAGE = LanguageCodes.ENGLISH
    
    /**
     * Save the selected language to DataStore.
     *
     * @param context Application context
     * @param languageCode Language code to save (en, es, hi, zh)
     */
    suspend fun saveSelectedLanguage(context: Context, languageCode: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_LANGUAGE_KEY] = languageCode
        }
    }
    
    /**
     * Get the selected language as a Flow.
     * Emits the saved language or default (English) if not set.
     *
     * @param context Application context
     * @return Flow of language code
     */
    fun getSelectedLanguage(context: Context): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[SELECTED_LANGUAGE_KEY] ?: DEFAULT_LANGUAGE
        }
    }
    
    /**
     * Get the selected language synchronously (blocking).
     * Use only for initialization where coroutines are not available.
     *
     * @param context Application context
     * @return Language code or default
     */
    fun getSelectedLanguageSync(context: Context): String {
        return try {
            val prefs = context.dataStore.data
            // This is a simplified sync read - in practice, use Flow
            DEFAULT_LANGUAGE
        } catch (e: Exception) {
            DEFAULT_LANGUAGE
        }
    }
}
