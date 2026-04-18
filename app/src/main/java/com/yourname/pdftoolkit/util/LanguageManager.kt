package com.yourname.pdftoolkit.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Language codes supported by the app.
 */
object LanguageCodes {
    const val ENGLISH = "en"
    const val SPANISH = "es"
    const val HINDI = "hi"
    const val CHINESE = "zh"
}

/**
 * Data class representing a language option.
 */
data class LanguageOption(
    val code: String,
    val name: String,
    val displayName: String
)

/**
 * LanguageManager handles runtime language switching using AndroidX locale API.
 * Uses AppCompatDelegate for per-app language preferences.
 */
object LanguageManager {

    /**
     * List of supported languages with their display names.
     */
    val supportedLanguages = listOf(
        LanguageOption(LanguageCodes.ENGLISH, "language_english", "English"),
        LanguageOption(LanguageCodes.SPANISH, "language_spanish", "Español"),
        LanguageOption(LanguageCodes.HINDI, "language_hindi", "हिन्दी"),
        LanguageOption(LanguageCodes.CHINESE, "language_chinese", "中文")
    )

    /**
     * Set the application language using AndroidX locale API.
     * This applies the language immediately and persists across app restarts.
     *
     * @param context Application context
     * @param langCode Language code (en, es, hi, zh)
     */
    fun setLanguage(context: Context, langCode: String) {
        val localeList = when (langCode) {
            LanguageCodes.ENGLISH -> LocaleListCompat.forLanguageTags(LanguageCodes.ENGLISH)
            LanguageCodes.SPANISH -> LocaleListCompat.forLanguageTags(LanguageCodes.SPANISH)
            LanguageCodes.HINDI -> LocaleListCompat.forLanguageTags(LanguageCodes.HINDI)
            LanguageCodes.CHINESE -> LocaleListCompat.forLanguageTags(LanguageCodes.CHINESE)
            else -> LocaleListCompat.forLanguageTags(LanguageCodes.ENGLISH)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Get the currently selected language code from AppCompatDelegate.
     *
     * @return The current language code (en, es, hi, zh)
     */
    fun getCurrentLanguage(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return when {
            locales.isEmpty -> LanguageCodes.ENGLISH
            locales.toLanguageTags().startsWith(LanguageCodes.CHINESE) -> LanguageCodes.CHINESE
            locales.toLanguageTags().startsWith(LanguageCodes.HINDI) -> LanguageCodes.HINDI
            locales.toLanguageTags().startsWith(LanguageCodes.SPANISH) -> LanguageCodes.SPANISH
            else -> LanguageCodes.ENGLISH
        }
    }

    /**
     * Get the currently selected language as a Flow from DataStore.
     * Use this for observing language changes in UI.
     *
     * @param context Application context
     * @return Flow of current language code
     */
    fun getLanguageFlow(context: Context): Flow<String> {
        return LanguageDataStore.getSelectedLanguage(context)
    }

    /**
     * Change the application language and persist the choice.
     * This should be called when user selects a new language.
     *
     * @param context Application context
     * @param langCode New language code to apply
     */
    suspend fun changeLanguage(context: Context, langCode: String) {
        // Save to DataStore for persistence
        LanguageDataStore.saveSelectedLanguage(context, langCode)
        // Apply via AppCompatDelegate
        setLanguage(context, langCode)
    }

    /**
     * Initialize language on app startup.
     * Should be called from Application.onCreate() before any UI is rendered.
     *
     * @param context Application context
     */
    fun initializeLanguage(context: Context) {
        // Check if AppCompatDelegate has a locale set
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        
        if (currentLocales.isEmpty) {
            // No locale set via AppCompatDelegate, check DataStore
            val savedLanguage = runBlocking {
                LanguageDataStore.getSelectedLanguage(context).first()
            }
            
            // Apply the saved language
            setLanguage(context, savedLanguage)
        }
        // If locales are already set, AppCompatDelegate handles persistence automatically
    }

    /**
     * Get the display name for a language code.
     *
     * @param langCode Language code
     * @return Display name for the language
     */
    fun getLanguageDisplayName(langCode: String): String {
        return supportedLanguages.find { it.code == langCode }?.displayName 
            ?: supportedLanguages.first().displayName
    }

    /**
     * Check if a language code is supported.
     *
     * @param langCode Language code to check
     * @return true if the language is supported
     */
    fun isSupportedLanguage(langCode: String): Boolean {
        return supportedLanguages.any { it.code == langCode }
    }
}
