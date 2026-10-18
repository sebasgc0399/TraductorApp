package com.sebas.traductorapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    /** Escala del tamaño de letra de los globos (0.8–1.3). */
    val fontScale: Float = 1f,
    /** La bienvenida ya se mostró (o no hace falta porque ya había una API key). */
    val onboardingDone: Boolean = false,
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gpt-6-luna"
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.3f
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
        val fontScale = floatPreferencesKey("font_scale")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setApiKey(key: String) = context.dataStore.edit {
        // Quien ya tuvo una key (o la guarda ahora) no vuelve a ver la bienvenida, aunque después la borre.
        if (it[Keys.onboarding] == null && (it[Keys.apiKey] != null || key.isNotBlank())) it[Keys.onboarding] = true
        if (key.isBlank()) it.remove(Keys.apiKey) else it[Keys.apiKey] = CryptoBox.encrypt(key.trim())
    }

    suspend fun setModel(model: String) = context.dataStore.edit {
        it[Keys.model] = model.trim().ifBlank { AppSettings.DEFAULT_MODEL }
    }

    suspend fun setVariant(variant: SpanishVariant) = context.dataStore.edit { it[Keys.variant] = variant.name }

    suspend fun setPrefetchAhead(n: Int) = context.dataStore.edit { it[Keys.prefetch] = n.coerceIn(1, 30) }

    suspend fun setTranslateSfx(enabled: Boolean) = context.dataStore.edit { it[Keys.sfx] = enabled }

    suspend fun setDebugBoxes(enabled: Boolean) = context.dataStore.edit { it[Keys.debug] = enabled }

    suspend fun setFontScale(scale: Float) = context.dataStore.edit {
        it[Keys.fontScale] = scale.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)
    }

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.onboarding] = done }

    private fun Preferences.toSettings() = AppSettings(
        apiKey = this[Keys.apiKey]?.let(CryptoBox::decrypt).orEmpty(),
        model = this[Keys.model] ?: AppSettings.DEFAULT_MODEL,
        variant = this[Keys.variant]?.let { v -> SpanishVariant.entries.firstOrNull { it.name == v } }
            ?: SpanishVariant.LATINO,
        prefetchAhead = this[Keys.prefetch] ?: 8,
        translateSfx = this[Keys.sfx] ?: false,
        debugBoxes = this[Keys.debug] ?: false,
        fontScale = (this[Keys.fontScale] ?: 1f).coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE),
        // Quien ya configuró su API key (instalaciones previas) no necesita la bienvenida.
        onboardingDone = this[Keys.onboarding] ?: (this[Keys.apiKey] != null),
    )
}
