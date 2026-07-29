package com.yourname.pdftoolkit.util

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Language codes supported by the app.
 * Kept as constants for readability/backwards-compatible references elsewhere in the
 * codebase. The single source of truth for behavior is [LanguageManager.supportedLanguages]
 * below — add a new language there (and its values-xx/strings.xml) and everything else
 * (setLanguage, getCurrentLanguage, the Settings picker) works automatically.
 */
object LanguageCodes {
    const val ENGLISH = "en"
    const val HINDI = "hi"
    const val CHINESE = "zh"
    const val PORTUGUESE = "pt-BR"
    const val GERMAN = "de"
    const val SPANISH = "es"
    const val RUSSIAN = "ru"
    const val UZBEK = "uz"
    const val TURKMEN = "tk"
    const val FRENCH = "fr"
    const val ARABIC = "ar"
    const val JAPANESE = "ja"
    const val KOREAN = "ko"
    const val INDONESIAN = "id"
    const val TURKISH = "tr"
}

/**
 * Data class representing a language option.
 *
 * @param code The language/locale tag used both as the BCP-47 tag passed to
 *   [LocaleListCompat.forLanguageTags] and as the values-xx resource qualifier
 *   (e.g. "en", "hi", "zh", "pt-BR", "de").
 * @param name Resource-name-style identifier for the language (used for logging/lookup).
 * @param displayName Native-script name shown to the user in the language picker.
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
     * This is the single source of truth: adding a language here (plus its matching
     * values-xx/strings.xml resource folder and an entry in locales_config.xml) is
     * all that's required — setLanguage(), getCurrentLanguage(), and the Settings
     * language picker all derive from this list automatically.
     */
    val supportedLanguages = listOf(
        LanguageOption(LanguageCodes.ENGLISH, "language_english", "English"),
        LanguageOption(LanguageCodes.HINDI, "language_hindi", "\u0939\u093F\u0928\u094D\u0926\u0940"),
        LanguageOption(LanguageCodes.CHINESE, "language_chinese", "\u4E2D\u6587"),
        LanguageOption(LanguageCodes.PORTUGUESE, "language_portuguese", "Portugu\u00EAs (Brasil)"),
        LanguageOption(LanguageCodes.GERMAN, "language_german", "Deutsch"),
        LanguageOption(LanguageCodes.SPANISH, "language_spanish", "Espa\u00F1ol"),
        LanguageOption(LanguageCodes.RUSSIAN, "language_russian", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
        LanguageOption(LanguageCodes.UZBEK, "language_uzbek", "O\u02BBzbekcha"),
        LanguageOption(LanguageCodes.TURKMEN, "language_turkmen", "T\u00FCrkmen\u00E7e"),
        LanguageOption(LanguageCodes.FRENCH, "language_french", "Fran\u00E7ais"),
        LanguageOption(LanguageCodes.ARABIC, "language_arabic", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"),
        LanguageOption(LanguageCodes.JAPANESE, "language_japanese", "\u65E5\u672C\u8A9E"),
        LanguageOption(LanguageCodes.KOREAN, "language_korean", "\uD55C\uAD6D\uC5B4"),
        LanguageOption(LanguageCodes.INDONESIAN, "language_indonesian", "Bahasa Indonesia"),
        LanguageOption(LanguageCodes.TURKISH, "language_turkish", "T\u00FCrk\u00E7e")
    )

    /**
     * Set the application language using AndroidX locale API.
     * This applies the language immediately and persists across app restarts.
     *
     * @param context Application context
     * @param langCode Language code — must match a [LanguageOption.code] in [supportedLanguages]
     */
    fun setLanguage(context: Context, langCode: String) {
        val resolvedCode = if (isSupportedLanguage(langCode)) langCode else LanguageCodes.ENGLISH
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(resolvedCode))
    }

    /**
     * Get the currently selected language code from AppCompatDelegate.
     *
     * @return The current language code, one of [supportedLanguages]
     */
    fun getCurrentLanguage(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return LanguageCodes.ENGLISH
        val languageTags = locales.toLanguageTags()
        // Match the longest code first (e.g. "pt-BR" before a hypothetical bare "pt")
        // so regional variants aren't shadowed by a shorter prefix.
        return supportedLanguages
            .sortedByDescending { it.code.length }
            .firstOrNull { languageTags.startsWith(it.code, ignoreCase = true) }
            ?.code
            ?: LanguageCodes.ENGLISH
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
     * IMPORTANT: This is a suspend function to ensure DataStore write completes
     * before locale is applied, preventing race conditions.
     *
     * @param context Application context
     * @param langCode New language code to apply
     */
    suspend fun changeLanguage(context: Context, langCode: String) {
        // Save to DataStore first to ensure persistence (await completion)
        LanguageDataStore.saveSelectedLanguage(context, langCode)

        // Apply the locale change via AppCompatDelegate.
        // NOTE: On API 33+ this is proxied through the framework's LocaleManager via a
        // binder IPC to system_server, which is asynchronous — the automatic activity
        // recreation callback can lag noticeably (1-3s, worse on some OEM skins).
        // Force an immediate recreate() rather than waiting on that callback so the UI
        // updates in the same frame as the confirmation toast.
        setLanguage(context, langCode)
        findActivity(context)?.recreate()

        Log.d("LanguageManager", "Language changed to: $langCode")
    }

    /**
     * Unwrap a Context to find the underlying Activity, if any.
     * Compose's LocalContext.current is usually the Activity directly, but this is
     * defensive against future ContextWrapper layers (e.g. added theming providers).
     */
    private fun findActivity(context: Context): android.app.Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? android.app.Activity
    }

    /**
     * Initialize language on app startup.
     * Should be called before Activity.onCreate() renders any UI.
     *
     * @param context Application context
     */
    fun initializeLanguage(context: Context) {
        val savedLanguage = runBlocking {
            LanguageDataStore.getSelectedLanguage(context).first()
        }
        setLanguage(context, savedLanguage)
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
