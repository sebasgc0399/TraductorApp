package com.sebas.traductorapp.translate

import com.sebas.traductorapp.data.SpanishVariant
import com.sebas.traductorapp.model.GlossaryEntry
import com.sebas.traductorapp.model.SourceLine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiTranslatorTest {
    private val prompt = BuiltPrompt(
        instructions = "INSTRUCTIONS",
        seriesBlock = "\n\n[SUMMARY]\nS\n\n[GLOSSARY]\nZed => Zed",
        variablePart = "[CONTEXT]\n(inicio)\n\n[TRANSLATE]\n1|HEY, ROOKIE!\n2|[QUEST COMPLETED]",
        ids = listOf(1, 2),
    )

    private val summaryRequest = SummaryRequest(
        variant = SpanishVariant.LATINO,
        seriesTitle = "Solo Leveling",
        currentSummary = "Jin-woo es un cazador de rango E.",
        recentLines = listOf("¡Oye, novato!", "¿Usted es el líder?"),
    )

    private fun params(model: String = "gpt-6-luna") =
        TranslateParams(apiKey = "sk-test", model = model, maxOutputTokens = 321, cacheKey = "series-key")

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun reply(outputText: String): String = buildString {
        append("""{"status":"completed","model":"gpt-6-luna","output":[{"type":"message","content":[{"type":"output_text","text":""")
        append(JsonPrimitive(outputText).toString())
        append("""}]}],"usage":{"input_tokens":10,"output_tokens":5}}""")
    }

    // ---------- parseResponseBody ----------

    @Test
    fun parsesRealisticResponse() {
        val r = OpenAiTranslator.parseResponseBody(SAMPLE_RESPONSE, model = "gpt-6-luna", latencyMs = 842)

        assertEquals(mapOf(1 to "¡Oye, novato!", 2 to "[Misión completada]", 3 to ""), r.translations)
        assertEquals(listOf(GlossaryEntry("Kang Jin", "Kang Jin")), r.newGlossary)
        assertEquals(1830, r.usage.inputTokens)
        assertEquals(1280, r.usage.cachedTokens)
        assertEquals(512, r.usage.cacheWriteTokens)
        assertEquals(95, r.usage.outputTokens)
        assertEquals(0, r.usage.reasoningTokens)
        assertEquals(842L, r.latencyMs)
        assertEquals("gpt-6-luna-2026-09-15", r.model)
    }

    @Test
    fun pipedItemsToleratePaddingDropBadItemsAndKeepFirstDuplicate() {
        val out = """{"t":[" 4 | Hola ","sin id","5|a|b","4|repetido","6|línea\ncon salto"],"g":[]}"""
        val r = OpenAiTranslator.parseResponseBody(reply(out), "gpt-6-luna", 1)
        assertEquals(mapOf(4 to "Hola", 5 to "a|b", 6 to "línea\ncon salto"), r.translations)
    }

    @Test
    fun parsesKeyedObjectShape() {
        val out = """{"t":{"1":"Hola"," 2 ":" Adiós ","x":"no","3":null},"g":[]}"""
        val r = OpenAiTranslator.parseResponseBody(reply(out), "gpt-6-luna", 1)
        assertEquals(mapOf(1 to "Hola", 2 to "Adiós"), r.translations)
    }

    @Test
    fun parsesLegacyObjectArrayShape() {
        val body = """
            {"status":"completed","output":[{"type":"message","role":"assistant","content":[
              {"type":"output_text","text":"{\"t\":[{\"i\":4,\"s\":\"Hola\",\"extra\":1},{\"i\":\"5\",\"s\":\" Chau \"}],\"g\":[]}"}]}],
             "usage":{"input_tokens":50,"output_tokens":7}}
        """.trimIndent()
        val r = OpenAiTranslator.parseResponseBody(body, model = "gpt-6-luna", latencyMs = 10)
        assertEquals(mapOf(4 to "Hola", 5 to "Chau"), r.translations)
        assertEquals(50, r.usage.inputTokens)
        assertEquals(0, r.usage.cachedTokens)
        assertEquals(0, r.usage.cacheWriteTokens)
        assertEquals(7, r.usage.outputTokens)
        assertEquals(0, r.usage.reasoningTokens)
        assertEquals("gpt-6-luna", r.model)
    }

    @Test
    fun missingTranslationsFieldIsRetryableError() {
        val e = assertThrows(TranslatorException::class.java) {
            OpenAiTranslator.parseResponseBody(reply("""{"g":[]}"""), "gpt-6-luna", 1)
        }
        assertTrue(e.retryable)
    }

    @Test
    fun refusalIsRetryableError() {
        val body = """
            {"status":"completed","model":"gpt-6-luna","output":[{"type":"message","role":"assistant",
             "content":[{"type":"refusal","refusal":"I can't help with that."}]}]}
        """.trimIndent()
        val e = assertThrows(TranslatorException::class.java) {
            OpenAiTranslator.parseResponseBody(body, "gpt-6-luna", 1)
        }
        assertTrue(e.retryable)
        assertTrue(e.message!!.contains("I can't help with that."))
    }

    @Test
    fun incompleteStatusIsRetryableError() {
        val body = """
            {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
             "output":[{"type":"message","content":[{"type":"output_text","text":"{\"t\":[\"1|Ho"}]}]}
        """.trimIndent()
        val e = assertThrows(TranslatorException::class.java) {
            OpenAiTranslator.parseResponseBody(body, "gpt-6-luna", 1)
        }
        assertTrue(e.retryable)
        assertTrue(e.message!!.contains("max_output_tokens"))
    }

    @Test
    fun garbageOutputIsRetryableError() {
        val body = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"not json"}]}]}"""
        val e = assertThrows(TranslatorException::class.java) {
            OpenAiTranslator.parseResponseBody(body, "gpt-6-luna", 1)
        }
        assertTrue(e.retryable)
    }

    // ---------- buildRequestBody ----------

    @Test
    fun requestBodyHasTwoBreakpointsInOrderAndStrictSchema() {
        val root = json(OpenAiTranslator.buildRequestBody(prompt, params(), useCacheFields = true, useReasoning = true))

        assertEquals("gpt-6-luna", root["model"]!!.jsonPrimitive.content)
        val input = root["input"]!!.jsonArray
        assertEquals(2, input.size)

        val developer = input[0].jsonObject
        assertEquals("developer", developer["role"]!!.jsonPrimitive.content)
        val parts = developer["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, parts.size)
        assertEquals(listOf(prompt.instructions, prompt.seriesBlock), parts.map { it["text"]!!.jsonPrimitive.content })
        for (part in parts) {
            assertEquals("input_text", part["type"]!!.jsonPrimitive.content)
            assertEquals("explicit", part["prompt_cache_breakpoint"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        }

        val user = input[1].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val userParts = user["content"]!!.jsonArray
        assertEquals(1, userParts.size)
        assertEquals(prompt.variablePart, userParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(userParts[0].jsonObject["prompt_cache_breakpoint"])

        val options = root["prompt_cache_options"]!!.jsonObject
        assertEquals("explicit", options["mode"]!!.jsonPrimitive.content)
        assertEquals("30m", options["ttl"]!!.jsonPrimitive.content)
        assertEquals("series-key", root["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("none", root["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(321, root["max_output_tokens"]!!.jsonPrimitive.int)
        assertFalse(root["store"]!!.jsonPrimitive.boolean)

        val format = root["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("translations", format["name"]!!.jsonPrimitive.content)
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
        val schema = format["schema"]!!.jsonObject
        assertEquals(setOf("t", "g"), schema["properties"]!!.jsonObject.keys)
        val t = schema["properties"]!!.jsonObject["t"]!!.jsonObject
        assertEquals("array", t["type"]!!.jsonPrimitive.content)
        assertEquals("string", t["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertStrict(schema)
    }

    @Test
    fun schemaIsIdenticalForAnyBatchSize() {
        // El esquema va dentro del prefijo cacheado: si cambiara con el lote, cada tamaño sería un cache miss.
        val small = PromptBuilder.build(SpanishVariant.LATINO, emptyList(), "", emptyList(), listOf(SourceLine(1, "HI")))
        val big = PromptBuilder.build(
            SpanishVariant.LATINO, emptyList(), "", emptyList(), List(40) { SourceLine(it + 1, "LINE $it") },
        )
        val a = json(OpenAiTranslator.buildRequestBody(small, params(), useCacheFields = true, useReasoning = true))
        val b = json(OpenAiTranslator.buildRequestBody(big, params(), useCacheFields = true, useReasoning = true))
        assertEquals(a["text"].toString(), b["text"].toString())
    }

    @Test
    fun requestBodyWithoutCacheFieldsSendsSingleDeveloperPart() {
        val body = OpenAiTranslator.buildRequestBody(prompt, params(), useCacheFields = false, useReasoning = false)
        val root = json(body)
        assertFalse(body.contains("prompt_cache"))
        assertFalse(root.containsKey("reasoning"))
        assertTrue(root.containsKey("text"))
        assertTrue(root.containsKey("max_output_tokens"))
        val parts = root["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals(prompt.stablePrefix, parts[0].jsonObject["text"]!!.jsonPrimitive.content)

        val onlyNoCache = json(OpenAiTranslator.buildRequestBody(prompt, params(), useCacheFields = false, useReasoning = true))
        assertTrue(onlyNoCache.containsKey("reasoning"))
        assertFalse(onlyNoCache.containsKey("prompt_cache_options"))
    }

    private fun assertStrict(schema: JsonObject) {
        if (schema["type"]?.jsonPrimitive?.content == "object") {
            val properties = schema["properties"]!!.jsonObject
            val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(properties.keys, required)
            assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
            properties.values.forEach { assertStrict(it.jsonObject) }
        }
        schema["items"]?.let { assertStrict(it.jsonObject) }
    }

    // ---------- resumen ----------

    @Test
    fun summaryRequestBody() {
        val body = OpenAiTranslator.buildSummaryRequestBody(summaryRequest, params(), useCacheFields = true, useReasoning = true)
        val root = json(body)
        val input = root["input"]!!.jsonArray
        assertEquals("developer", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            PromptBuilder.summaryInstructions(SpanishVariant.LATINO),
            input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("user", input[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            PromptBuilder.summaryInput(summaryRequest),
            input[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        // Modo explícito sin breakpoints: sin caché y sin escrituras de caché.
        assertEquals("explicit", root["prompt_cache_options"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse(body.contains("prompt_cache_breakpoint"))
        assertFalse(root.containsKey("prompt_cache_key"))
        assertEquals("none", root["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(400, root["max_output_tokens"]!!.jsonPrimitive.int)
        assertFalse(root["store"]!!.jsonPrimitive.boolean)

        val format = root["text"]!!.jsonObject["format"]!!.jsonObject
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
        val schema = format["schema"]!!.jsonObject
        assertEquals(setOf("summary"), schema["properties"]!!.jsonObject.keys)
        assertStrict(schema)

        val noCache = OpenAiTranslator.buildSummaryRequestBody(summaryRequest, params(), useCacheFields = false, useReasoning = false)
        assertFalse(noCache.contains("prompt_cache"))
        assertFalse(json(noCache).containsKey("reasoning"))
    }

    @Test
    fun parsesSummaryResponse() {
        val r = OpenAiTranslator.parseSummaryResponseBody(SUMMARY_RESPONSE, "gpt-6-luna", latencyMs = 77)
        assertEquals("Jin-woo (tú con Ju-hee, usted con el líder) entra a un portal doble.", r.summary)
        assertEquals(900, r.usage.inputTokens)
        assertEquals(60, r.usage.outputTokens)
        assertEquals(77L, r.latencyMs)
        assertEquals("gpt-6-luna-2026-09-15", r.model)

        val e = assertThrows(TranslatorException::class.java) {
            OpenAiTranslator.parseSummaryResponseBody(reply("""{"summary":"  "}"""), "gpt-6-luna", 1)
        }
        assertTrue(e.retryable)
    }

    @Test
    fun summarizeCallsApiAndSkipsWhenThereAreNoLines() {
        val seen = mutableListOf<String>()
        val translator = OpenAiTranslator(fakeClient(seen, 200 to SUMMARY_RESPONSE))

        val r = runBlocking { translator.summarize(summaryRequest, params()) }
        assertEquals(1, seen.size)
        assertTrue(seen[0].contains("series_summary"))
        assertEquals("Jin-woo (tú con Ju-hee, usted con el líder) entra a un portal doble.", r.summary)

        val empty = runBlocking { translator.summarize(summaryRequest.copy(recentLines = listOf(" ")), params()) }
        assertEquals(1, seen.size)
        assertEquals(summaryRequest.currentSummary, empty.summary)
        assertEquals(0, empty.usage.inputTokens)
    }

    @Test
    fun summaryCacheRejectionDoesNotDisableTranslationCache() {
        val seen = mutableListOf<String>()
        val unknownParam = """{"error":{"message":"Unknown parameter: 'prompt_cache_options'.","type":"invalid_request_error","param":"prompt_cache_options","code":"unknown_parameter"}}"""
        val translator = OpenAiTranslator(
            fakeClient(seen, 400 to unknownParam, 200 to SUMMARY_RESPONSE, 200 to SAMPLE_RESPONSE),
        )
        val p = params(model = "test-model-summary-no-cache")

        runBlocking { translator.summarize(summaryRequest, p) }
        assertEquals(2, seen.size)
        assertFalse(seen[1].contains("prompt_cache"))

        runBlocking { translator.translate(prompt, p) }
        assertEquals(3, seen.size)
        assertTrue(seen[2].contains("prompt_cache_breakpoint"))
    }

    // ---------- translate() con un cliente falso (sin red) ----------

    private fun fakeClient(seen: MutableList<String>, vararg replies: Pair<Int, String>): OkHttpClient {
        val queue = ArrayDeque(replies.toList())
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    val (code, body) = synchronized(queue) {
                        synchronized(seen) { seen.add(buffer.readUtf8()) }
                        queue.removeFirst()
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fake")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
    }

    @Test
    fun retriesWithoutCacheFieldsAndRemembersIt() {
        val seen = mutableListOf<String>()
        val unknownParam = """{"error":{"message":"Unknown parameter: 'prompt_cache_options'.","type":"invalid_request_error","param":"prompt_cache_options","code":"unknown_parameter"}}"""
        val translator = OpenAiTranslator(fakeClient(seen, 400 to unknownParam, 200 to SAMPLE_RESPONSE, 200 to SAMPLE_RESPONSE))
        val p = params(model = "test-model-no-cache")

        val r = runBlocking { translator.translate(prompt, p) }
        // El id 3 no se pidió en este lote y se descarta.
        assertEquals(mapOf(1 to "¡Oye, novato!", 2 to "[Misión completada]"), r.translations)
        assertEquals(2, seen.size)
        assertTrue(seen[0].contains("prompt_cache_options"))
        assertFalse(seen[1].contains("prompt_cache"))
        val fallbackParts = json(seen[1])["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, fallbackParts.size)
        assertEquals(prompt.stablePrefix, fallbackParts[0].jsonObject["text"]!!.jsonPrimitive.content)

        runBlocking { translator.translate(prompt, p) }
        assertEquals(3, seen.size)
        assertFalse(seen[2].contains("prompt_cache"))
    }

    @Test
    fun retriesWithoutReasoning() {
        val seen = mutableListOf<String>()
        val unsupported = """{"error":{"message":"Unsupported value: 'none' is not supported with this model.","type":"invalid_request_error","param":"reasoning.effort","code":"unsupported_value"}}"""
        val translator = OpenAiTranslator(fakeClient(seen, 400 to unsupported, 200 to SAMPLE_RESPONSE))

        runBlocking { translator.translate(prompt, params(model = "test-model-no-reasoning")) }
        assertEquals(2, seen.size)
        assertTrue(json(seen[0]).containsKey("reasoning"))
        assertFalse(json(seen[1]).containsKey("reasoning"))
        assertTrue(json(seen[1]).containsKey("prompt_cache_options"))
    }

    @Test
    fun mapsHttpErrors() {
        val invalidKey = """{"error":{"message":"Incorrect API key provided.","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}"""
        val e401 = assertThrows(TranslatorException::class.java) {
            runBlocking { OpenAiTranslator(fakeClient(mutableListOf(), 401 to invalidKey)).translate(prompt, params()) }
        }
        assertEquals(401, e401.httpCode)
        assertFalse(e401.retryable)
        assertTrue(e401.message!!.startsWith("API key inválida o revocada"))
        assertTrue(e401.message!!.contains("Incorrect API key provided."))

        val quota = """{"error":{"message":"You exceeded your current quota.","type":"insufficient_quota","param":null,"code":"insufficient_quota"}}"""
        val eQuota = assertThrows(TranslatorException::class.java) {
            runBlocking { OpenAiTranslator(fakeClient(mutableListOf(), 429 to quota)).translate(prompt, params()) }
        }
        assertFalse(eQuota.retryable)
        assertTrue(eQuota.message!!.startsWith("Sin saldo en la cuenta de OpenAI"))

        val rate = """{"error":{"message":"Rate limit reached.","type":"requests","param":null,"code":"rate_limit_exceeded"}}"""
        val eRate = assertThrows(TranslatorException::class.java) {
            runBlocking { OpenAiTranslator(fakeClient(mutableListOf(), 429 to rate)).translate(prompt, params()) }
        }
        assertTrue(eRate.retryable)
        assertTrue(eRate.message!!.startsWith("Límite de velocidad"))

        val e503 = assertThrows(TranslatorException::class.java) {
            runBlocking { OpenAiTranslator(fakeClient(mutableListOf(), 503 to "<html>down</html>")).translate(prompt, params()) }
        }
        assertEquals(503, e503.httpCode)
        assertTrue(e503.retryable)

        // El resumen usa el mismo mapeo de errores.
        val eSummary = assertThrows(TranslatorException::class.java) {
            runBlocking { OpenAiTranslator(fakeClient(mutableListOf(), 401 to invalidKey)).summarize(summaryRequest, params()) }
        }
        assertEquals(401, eSummary.httpCode)
        assertFalse(eSummary.retryable)
    }

    private companion object {
        val SAMPLE_RESPONSE = """
            {
              "id": "resp_67ccd2bed1ec8190b14f964abc054267",
              "object": "response",
              "created_at": 1792310400,
              "status": "completed",
              "error": null,
              "incomplete_details": null,
              "model": "gpt-6-luna-2026-09-15",
              "output": [
                {
                  "id": "rs_67ccd2bf17f0819081ff3bb2cf6508e6",
                  "type": "reasoning",
                  "summary": []
                },
                {
                  "id": "msg_67ccd2bf17f0819081ff3bb2cf6508e7",
                  "type": "message",
                  "status": "completed",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "output_text",
                      "annotations": [],
                      "text": "{\"t\":[\"1| ¡Oye, novato! \",\"2|[Misión completada]\",\"3|\"],\"g\":[{\"en\":\"Kang Jin\",\"es\":\"Kang Jin\"},{\"en\":\" \",\"es\":\"x\"}]}"
                    }
                  ]
                }
              ],
              "reasoning": {"effort": "none", "summary": null},
              "store": false,
              "usage": {
                "input_tokens": 1830,
                "input_tokens_details": {"cached_tokens": 1280, "cache_write_tokens": 512},
                "output_tokens": 95,
                "output_tokens_details": {"reasoning_tokens": 0},
                "total_tokens": 1925
              }
            }
        """.trimIndent()

        val SUMMARY_RESPONSE = """
            {"status":"completed","model":"gpt-6-luna-2026-09-15","output":[{"type":"message","role":"assistant","content":[
              {"type":"output_text","text":"{\"summary\":\" Jin-woo (tú con Ju-hee, usted con el líder) entra a un portal doble. \"}"}]}],
             "usage":{"input_tokens":900,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens":60}}
        """.trimIndent()
    }
}
