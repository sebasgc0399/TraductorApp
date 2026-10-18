package com.sebas.traductorapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class SpanishVariant(val label: String) {
    LATINO("Español latino neutro"),
    ESPANA("Español de España"),
}

data class AppSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val variant: SpanishVariant = SpanishVariant.LATINO,
    val prefetchAhead: Int = 8,
    val translateSfx: Boolean = false,
    val debugBoxes: Boolean = false,
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gpt-6-luna"
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val apiKey = stringPreferencesKey("api_key_enc")
        val model = stringPreferencesKey("model")
        val variant = stringPreferencesKey("variant")
        val prefetch = intPreferencesKey("prefetch_ahead")
        val sfx = booleanPreferencesKey("translate_sfx")
        val debug = booleanPreferencesKey("debug_boxes")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setApiKey(key: String) = context.dataStore.edit {
        if (key.isBlank()) it.remove(Keys.apiKey) else it[Keys.apiKey] = CryptoBox.encrypt(key.trim())
    }

    suspend fun setModel(model: String) = context.dataStore.edit {
        it[Keys.model] = model.trim().ifBlank { AppSettings.DEFAULT_MODEL }
    }

    suspend fun setVariant(variant: SpanishVariant) = context.dataStore.edit { it[Keys.variant] = variant.name }

    suspend fun setPrefetchAhead(n: Int) = context.dataStore.edit { it[Keys.prefetch] = n.coerceIn(1, 30) }

    suspend fun setTranslateSfx(enabled: Boolean) = context.dataStore.edit { it[Keys.sfx] = enabled }

    suspend fun setDebugBoxes(enabled: Boolean) = context.dataStore.edit { it[Keys.debug] = enabled }

    private fun Preferences.toSettings() = AppSettings(
        apiKey = this[Keys.apiKey]?.let(CryptoBox::decrypt).orEmpty(),
        model = this[Keys.model] ?: AppSettings.DEFAULT_MODEL,
        variant = this[Keys.variant]?.let { v -> SpanishVariant.entries.firstOrNull { it.name == v } }
            ?: SpanishVariant.LATINO,
        prefetchAhead = this[Keys.prefetch] ?: 8,
        translateSfx = this[Keys.sfx] ?: false,
        debugBoxes = this[Keys.debug] ?: false,
    )
}
