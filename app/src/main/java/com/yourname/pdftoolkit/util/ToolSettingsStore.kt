package com.yourname.pdftoolkit.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists per-tool settings so tools remember their last-used options (#122).
 * Backed by DataStore; keys are namespaced per tool to avoid collisions.
 */
object ToolSettingsStore {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_settings")

    suspend fun saveString(context: Context, tool: String, field: String, value: String) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("${tool}_$field")] = value
        }
    }

    suspend fun loadString(context: Context, tool: String, field: String): String? {
        return context.dataStore.data.map { prefs ->
            prefs[stringPreferencesKey("${tool}_$field")]
        }.first()
    }

    suspend fun saveBoolean(context: Context, tool: String, field: String, value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[booleanPreferencesKey("${tool}_$field")] = value
        }
    }

    suspend fun loadBoolean(context: Context, tool: String, field: String): Boolean? {
        return context.dataStore.data.map { prefs ->
            prefs[booleanPreferencesKey("${tool}_$field")]
        }.first()
    }
}
