package com.sebas.traductorapp.ui

import com.sebas.traductorapp.data.UsageLogEntity
import java.time.Instant
import java.util.Locale

/** Exporta el registro de consumo a CSV (RFC 4180, separador coma, fin de línea \n). */
object UsageCsv {
    const val HEADER =
        "timestamp_iso,series,chapter,model,bubbles,input_tokens,cached_tokens," +
            "cache_write_tokens,output_tokens,reasoning_tokens,latency_ms,cost_usd"

    fun build(rows: List<UsageLogEntity>): String = buildString {
        append(HEADER).append('\n')
        for (r in rows) {
            val fields = listOf(
                Instant.ofEpochMilli(r.timestamp).toString(),
                r.seriesKey,
                r.chapter.orEmpty(),
                r.model,
                r.bubbles.toString(),
                r.inputTokens.toString(),
                r.cachedTokens.toString(),
                r.cacheWriteTokens.toString(),
                r.outputTokens.toString(),
                r.reasoningTokens.toString(),
                r.latencyMs.toString(),
                String.format(Locale.US, "%.6f", r.costUsd),
            )
            fields.joinTo(this, separator = ",") { escape(it) }
            append('\n')
        }
    }

    /** Entre comillas solo si hace falta; las comillas internas se duplican. */
    fun escape(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }
}
