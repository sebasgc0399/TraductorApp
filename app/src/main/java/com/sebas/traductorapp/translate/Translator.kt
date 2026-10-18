package com.sebas.traductorapp.translate

import com.sebas.traductorapp.data.SpanishVariant
import com.sebas.traductorapp.model.TokenUsage
import com.sebas.traductorapp.model.TranslationResult

/**
 * Prompt en tres tramos para el caché con dos breakpoints: [instructions] (fijas por variante, se cachean solas),
 * [seriesBlock] (resumen + glosario, cambia de vez en cuando) y [variablePart] (contexto + globos, cambia siempre).
 */
data class BuiltPrompt(
    val instructions: String,
    val seriesBlock: String,
    val variablePart: String,
    val ids: List<Int>,
) {
    val stablePrefix: String get() = instructions + seriesBlock
}

data class TranslateParams(
    val apiKey: String,
    val model: String,
    val maxOutputTokens: Int,
    val cacheKey: String,
)

/** Pedido de actualización del resumen de la serie; [recentLines] son líneas ya traducidas al español. */
data class SummaryRequest(
    val variant: SpanishVariant,
    val seriesTitle: String,
    val currentSummary: String,
    val recentLines: List<String>,
)

data class SummaryResult(
    val summary: String,
    val usage: TokenUsage,
    val latencyMs: Long,
    val model: String,
)

/**
 * Error de traducción con mensaje en español apto para mostrar al usuario.
 * [badOutput]: OpenAI respondió (y cobró) pero la salida no sirvió: cortada, JSON inválido, rechazo o sin ids.
 * Solo estos errores cuentan como intento fallido de los globos; los de red/límite se reintentan sin descartar nada.
 * [usage]/[model]: tokens cobrados por esa respuesta fallida, para que el registro de consumo no quede corto.
 */
class TranslatorException(
    message: String,
    val httpCode: Int? = null,
    val retryable: Boolean = false,
    val badOutput: Boolean = false,
    val usage: TokenUsage? = null,
    val model: String? = null,
) : Exception(message)

interface Translator {
    suspend fun translate(prompt: BuiltPrompt, params: TranslateParams): TranslationResult

    /** Actualiza el resumen corrido de la serie. De [params] solo usa apiKey y model. */
    suspend fun summarize(request: SummaryRequest, params: TranslateParams): SummaryResult
}
