package com.sebas.traductorapp.translate

import com.sebas.traductorapp.model.GlossaryEntry
import com.sebas.traductorapp.model.TokenUsage
import com.sebas.traductorapp.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cliente de la Responses API de OpenAI con Structured Outputs y caché de prompt explícito. */
class OpenAiTranslator(private val http: OkHttpClient) : Translator {

    override suspend fun translate(prompt: BuiltPrompt, params: TranslateParams): TranslationResult {
        if (prompt.ids.isEmpty()) {
            return TranslationResult(emptyMap(), emptyList(), ZERO_USAGE, 0L, params.model)
        }
        return post(
            params,
            noCacheFieldModels,
            { useCache, useReasoning -> buildRequestBody(prompt, params, useCache, useReasoning) },
        ) { body, latencyMs ->
            val result = parseResponseBody(body, params.model, latencyMs)
            val wanted = prompt.ids.toHashSet()
            val translations = result.translations.filterKeys { it in wanted }
            if (translations.isEmpty()) {
                throw TranslatorException("El modelo no devolvió traducciones para este lote", retryable = true)
            }
            result.copy(translations = translations)
        }
    }

    override suspend fun summarize(request: SummaryRequest, params: TranslateParams): SummaryResult {
        if (request.recentLines.none { it.isNotBlank() }) {
            return SummaryResult(request.currentSummary.trim(), ZERO_USAGE, 0L, params.model)
        }
        return post(
            params,
            noSummaryCacheModels,
            { useCache, useReasoning -> buildSummaryRequestBody(request, params, useCache, useReasoning) },
        ) { body, latencyMs -> parseSummaryResponseBody(body, params.model, latencyMs) }
    }

    /**
     * POST a /responses con hasta 2 reintentos ante un 400: uno sin campos de caché y otro sin reasoning.
     * El rechazo se recuerda por modelo en [noCache] y en [noReasoningModels] durante todo el proceso.
     */
    private suspend fun <T> post(
        params: TranslateParams,
        noCache: MutableSet<String>,
        body: (useCache: Boolean, useReasoning: Boolean) -> String,
        parse: (body: String, latencyMs: Long) -> T,
    ): T {
        if (params.apiKey.isBlank()) throw TranslatorException("Falta la API key de OpenAI")

        val modelKey = params.model.trim().lowercase()
        var useCache = modelKey !in noCache
        var useReasoning = modelKey !in noReasoningModels

        for (attempt in 0..2) {
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer ${params.apiKey.trim()}")
                .post(body(useCache, useReasoning).toRequestBody(JSON_MEDIA))
                .build()

            val started = System.nanoTime()
            val reply = try {
                execute(request)
            } catch (e: IOException) {
                throw TranslatorException("Error de red: ${e.message ?: e.javaClass.simpleName}", retryable = true)
            }
            val latencyMs = (System.nanoTime() - started) / 1_000_000

            if (reply.code in 200..299) {
                return try {
                    parse(reply.body, latencyMs)
                } catch (e: TranslatorException) {
                    // Respuesta cobrada pero inutilizable: se marca como salida defectuosa con sus tokens.
                    throw TranslatorException(
                        e.message ?: "Salida inválida de OpenAI",
                        httpCode = reply.code,
                        retryable = true,
                        badOutput = true,
                        usage = usageFromBody(reply.body),
                        model = params.model,
                    )
                }
            }

            val error = ApiError.parse(reply.body)
            if (reply.code == 400) {
                if (useCache && error.mentionsCacheFields()) {
                    noCache.add(modelKey)
                    useCache = false
                    continue
                }
                if (useReasoning && error.mentionsReasoning()) {
                    noReasoningModels.add(modelKey)
                    useReasoning = false
                    continue
                }
            }
            throw httpError(reply.code, error, params.model)
        }
        throw TranslatorException("OpenAI rechazó la solicitud tras varios intentos")
    }

    /** Ejecuta la llamada sin bloquear y la cancela en OkHttp si se cancela la corrutina. */
    private suspend fun execute(request: Request): HttpReply = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine<HttpReply> { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use { HttpReply(it.code, it.body.string()) }
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                        return
                    }
                    cont.resume(reply)
                }
            })
        }
    }

    private class HttpReply(val code: Int, val body: String)

    private class ApiError(val message: String?, val type: String?, val code: String?, val param: String?) {
        private val haystack: String = listOfNotNull(message, param, code).joinToString(" ").lowercase()

        val isQuotaExceeded: Boolean get() = code == "insufficient_quota" || type == "insufficient_quota"

        fun mentionsCacheFields(): Boolean =
            "prompt_cache" in haystack || ("unknown parameter" in haystack && "cache" in haystack)

        fun mentionsReasoning(): Boolean = "reasoning" in haystack

        companion object {
            fun parse(body: String): ApiError {
                val error = parseObject(body).obj("error")
                return ApiError(error.str("message"), error.str("type"), error.str("code"), error.str("param"))
            }
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/responses"
        private const val SUMMARY_MAX_OUTPUT_TOKENS = 400
        private val JSON_MEDIA = "application/json".toMediaType()
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private val ZERO_USAGE = TokenUsage(0, 0, 0, 0, 0)

        // "<id>|<texto>", tolerando espacios alrededor del id.
        private val PIPED_ITEM = Regex("""^\s*(\d{1,6})\s*\|(.*)$""", RegexOption.DOT_MATCHES_ALL)

        // Modelos que rechazaron los campos de caché o de reasoning; se recuerda durante todo el proceso.
        // El resumen lleva su propio registro para que un rechazo suyo no apague el caché de las traducciones.
        private val noCacheFieldModels: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val noSummaryCacheModels: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val noReasoningModels: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Esquema estricto y FIJO: {"t":["<id>|<texto>"],"g":[{"en":string,"es":string}]}. El esquema forma parte del
         * prefijo cacheado (cambiarlo da cache miss "text_format_changed"), así que no depende del tamaño del lote.
         */
        private val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("t") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("g") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("en") { put("type", "string") }
                            putJsonObject("es") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add("en")
                            add("es")
                        }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") {
                add("t")
                add("g")
            }
            put("additionalProperties", false)
        }

        private val SUMMARY_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("summary") { put("type", "string") }
            }
            putJsonArray("required") { add("summary") }
            put("additionalProperties", false)
        }

        fun buildRequestBody(
            prompt: BuiltPrompt,
            params: TranslateParams,
            useCacheFields: Boolean,
            useReasoning: Boolean,
        ): String = buildJsonObject {
            // Breakpoint 1: instrucciones (≥ 1,024 tokens). Breakpoint 2: resumen + glosario.
            // Si cambia el bloque de la serie, la búsqueda cae al breakpoint 1 y las instrucciones siguen en caché.
            val developerParts = if (useCacheFields) {
                listOf(prompt.instructions, prompt.seriesBlock).filter { it.isNotEmpty() }
            } else {
                listOf(prompt.stablePrefix)
            }
            put("model", params.model)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "developer")
                    putJsonArray("content") {
                        developerParts.forEach { addText(it, breakpoint = useCacheFields) }
                    }
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") { addText(prompt.variablePart, breakpoint = false) }
                }
            }
            if (useCacheFields) {
                putJsonObject("prompt_cache_options") {
                    put("mode", "explicit")
                    put("ttl", "30m")
                }
                if (params.cacheKey.isNotBlank()) put("prompt_cache_key", params.cacheKey)
            }
            if (useReasoning) putJsonObject("reasoning") { put("effort", "none") }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "translations")
                    put("schema", SCHEMA)
                    put("strict", true)
                }
            }
            put("max_output_tokens", params.maxOutputTokens)
            put("store", false)
        }.toString()

        fun buildSummaryRequestBody(
            request: SummaryRequest,
            params: TranslateParams,
            useCacheFields: Boolean,
            useReasoning: Boolean,
        ): String = buildJsonObject {
            put("model", params.model)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "developer")
                    putJsonArray("content") { addText(PromptBuilder.summaryInstructions(request.variant), breakpoint = false) }
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") { addText(PromptBuilder.summaryInput(request), breakpoint = false) }
                }
            }
            // Modo explícito sin breakpoints: la llamada no usa caché ni paga escrituras (1.25×) que nadie reusaría.
            if (useCacheFields) putJsonObject("prompt_cache_options") { put("mode", "explicit") }
            if (useReasoning) putJsonObject("reasoning") { put("effort", "none") }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "series_summary")
                    put("schema", SUMMARY_SCHEMA)
                    put("strict", true)
                }
            }
            put("max_output_tokens", SUMMARY_MAX_OUTPUT_TOKENS)
            put("store", false)
        }.toString()

        private fun JsonArrayBuilder.addText(text: String, breakpoint: Boolean) {
            addJsonObject {
                put("type", "input_text")
                put("text", text)
                if (breakpoint) putJsonObject("prompt_cache_breakpoint") { put("mode", "explicit") }
            }
        }

        fun parseResponseBody(body: String, model: String, latencyMs: Long): TranslationResult {
            val root = parseObject(body)
                ?: throw TranslatorException("Respuesta ilegible de OpenAI", retryable = true)
            val payload = parsePayload(root, "El modelo se negó a traducir este lote")

            val translations = LinkedHashMap<Int, String>()
            fun keep(id: Int?, text: String?) {
                if (id != null && text != null && id !in translations) translations[id] = OutputText.clean(text)
            }
            when (val t = payload["t"]) {
                // Formato actual: ["<id>|<texto>"]. También acepta [{"i":id,"s":texto}] (formato anterior).
                is JsonArray -> for (element in t) {
                    when (element) {
                        is JsonPrimitive -> PIPED_ITEM.matchEntire(element.contentOrNull.orEmpty())?.let {
                            keep(it.groupValues[1].toIntOrNull(), it.groupValues[2])
                        }
                        is JsonObject -> keep(element.intAt("i"), element.str("s"))
                        else -> Unit
                    }
                }
                // Por robustez, también {"<id>": "texto"}.
                is JsonObject -> for ((key, value) in t) {
                    keep(key.trim().toIntOrNull(), (value as? JsonPrimitive)?.contentOrNull)
                }
                else -> throw TranslatorException("La salida del modelo no trae traducciones", retryable = true)
            }

            val glossary = (payload["g"] as? JsonArray).orEmpty()
                .mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val en = o.str("en")?.trim().orEmpty()
                    val es = o.str("es")?.trim().orEmpty()
                    if (en.isEmpty() || es.isEmpty()) null else GlossaryEntry(en, es)
                }
                .distinctBy { it.en.lowercase() }

            return TranslationResult(
                translations = translations,
                newGlossary = glossary,
                usage = parseUsage(root),
                latencyMs = latencyMs,
                model = root.str("model")?.takeIf { it.isNotBlank() } ?: model,
            )
        }

        fun parseSummaryResponseBody(body: String, model: String, latencyMs: Long): SummaryResult {
            val root = parseObject(body)
                ?: throw TranslatorException("Respuesta ilegible de OpenAI", retryable = true)
            val payload = parsePayload(root, "El modelo se negó a resumir la serie")
            val summary = payload.str("summary")?.trim().orEmpty()
            if (summary.isEmpty()) throw TranslatorException("El modelo no devolvió un resumen", retryable = true)
            return SummaryResult(
                summary = summary,
                usage = parseUsage(root),
                latencyMs = latencyMs,
                model = root.str("model")?.takeIf { it.isNotBlank() } ?: model,
            )
        }

        /** Valida el estado de la respuesta y devuelve el JSON que generó el modelo. */
        private fun parsePayload(root: JsonObject, refusalMessage: String): JsonObject {
            val status = root.str("status")
            if (status == "failed") {
                val reason = root.obj("error").str("message")
                throw TranslatorException("OpenAI no pudo completar la respuesta" + detail(reason), retryable = true)
            }

            val text = StringBuilder()
            var refusal: String? = null
            val output = root["output"] as? JsonArray
            if (output != null) {
                for (item in output) {
                    val message = item as? JsonObject ?: continue
                    if (message.str("type") != "message") continue
                    val content = message["content"] as? JsonArray ?: continue
                    for (part in content) {
                        val p = part as? JsonObject ?: continue
                        when (p.str("type")) {
                            "output_text" -> text.append(p.str("text").orEmpty())
                            "refusal" -> refusal = p.str("refusal").orEmpty()
                        }
                    }
                }
            }

            if (refusal != null) throw TranslatorException(refusalMessage + detail(refusal), retryable = true)
            if (status == "incomplete") {
                val reason = root.obj("incomplete_details").str("reason")?.takeIf { it.isNotBlank() }
                val suffix = if (reason == null) "" else " (motivo: $reason)"
                throw TranslatorException("Respuesta incompleta de OpenAI$suffix", retryable = true)
            }

            val outputText = text.toString().ifBlank { root.str("output_text").orEmpty() }
            if (outputText.isBlank()) throw TranslatorException("OpenAI devolvió una respuesta vacía", retryable = true)

            return parseObject(extractJson(outputText))
                ?: throw TranslatorException("La salida del modelo no es JSON válido", retryable = true)
        }

        /** Tokens cobrados según el cuerpo de una respuesta 200, aunque su salida no se pueda usar. */
        internal fun usageFromBody(body: String): TokenUsage? = parseObject(body)?.let(::parseUsage)

        private fun parseUsage(root: JsonObject): TokenUsage {
            val usage = root.obj("usage")
            val inputDetails = usage.obj("input_tokens_details")
            val outputDetails = usage.obj("output_tokens_details")
            return TokenUsage(
                inputTokens = usage.intAt("input_tokens") ?: 0,
                cachedTokens = inputDetails.intAt("cached_tokens") ?: 0,
                cacheWriteTokens = inputDetails.intAt("cache_write_tokens") ?: 0,
                outputTokens = usage.intAt("output_tokens") ?: 0,
                reasoningTokens = outputDetails.intAt("reasoning_tokens") ?: 0,
            )
        }

        private fun httpError(code: Int, error: ApiError, model: String): TranslatorException {
            val extra = detail(error.message)
            return when {
                code == 401 -> TranslatorException("API key inválida o revocada$extra", code, retryable = false)
                code == 429 && error.isQuotaExceeded ->
                    TranslatorException("Sin saldo en la cuenta de OpenAI$extra", code, retryable = false)
                code == 429 -> TranslatorException("Límite de velocidad, reintentando…$extra", code, retryable = true)
                code == 403 -> TranslatorException("OpenAI denegó el acceso (HTTP 403)$extra", code, retryable = false)
                code == 404 -> TranslatorException("El modelo «$model» no está disponible$extra", code, retryable = false)
                code == 408 || code == 409 ->
                    TranslatorException("OpenAI tardó demasiado (HTTP $code)$extra", code, retryable = true)
                code >= 500 -> TranslatorException("OpenAI no está disponible (HTTP $code)$extra", code, retryable = true)
                else -> TranslatorException("OpenAI rechazó la solicitud (HTTP $code)$extra", code, retryable = false)
            }
        }

        private fun detail(message: String?): String {
            val m = message?.trim().orEmpty()
            return if (m.isEmpty()) "" else " (OpenAI: ${m.take(300)})"
        }

        /** Tolera texto alrededor del JSON (por ejemplo, bloques ```json). */
        private fun extractJson(text: String): String {
            val t = text.trim()
            if (t.startsWith("{")) return t
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            return if (start >= 0 && end > start) t.substring(start, end + 1) else t
        }

        private fun parseObject(text: String): JsonObject? =
            try {
                JSON.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            }
    }
}

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.intAt(key: String): Int? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
}
