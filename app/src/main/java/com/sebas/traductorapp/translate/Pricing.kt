package com.sebas.traductorapp.translate

import com.sebas.traductorapp.model.TokenUsage

/** Precios oficiales (USD por 1M tokens) verificados el 18-oct-2026. */
object Pricing {
    data class Rates(
        val input: Double,
        val cachedInput: Double,
        val cacheWrite: Double,
        val output: Double,
    )

    private val GPT_6_LUNA = Rates(input = 0.10, cachedInput = 0.01, cacheWrite = 0.125, output = 0.50)

    // Los modelos sin cargo aparte por escritura de caché la cobran como entrada normal.
    private val TABLE: List<Pair<String, Rates>> = listOf(
        "gpt-6-luna" to GPT_6_LUNA,
        "gpt-5.6-luna" to Rates(input = 0.20, cachedInput = 0.02, cacheWrite = 0.25, output = 1.20),
        "gpt-5.4-mini" to Rates(input = 0.75, cachedInput = 0.075, cacheWrite = 0.75, output = 4.50),
        "gpt-5-mini" to Rates(input = 0.25, cachedInput = 0.025, cacheWrite = 0.25, output = 2.00),
        "gpt-5-nano" to Rates(input = 0.05, cachedInput = 0.005, cacheWrite = 0.05, output = 0.40),
    ).sortedByDescending { it.first.length } // la clave más específica gana

    /** Busca por nombre base, de modo que los snapshots ("gpt-6-luna-2026-09-15") resuelven igual. */
    fun rates(model: String): Rates {
        val name = model.trim().lowercase()
        return TABLE.firstOrNull { (key, _) -> name.startsWith(key) }?.second
            ?: TABLE.firstOrNull { (key, _) -> name.contains(key) }?.second
            ?: GPT_6_LUNA
    }

    fun costUsd(model: String, usage: TokenUsage): Double {
        val r = rates(model)
        val cached = usage.cachedTokens.coerceAtLeast(0).toDouble()
        val written = usage.cacheWriteTokens.coerceAtLeast(0).toDouble()
        val uncached = (usage.inputTokens.toDouble() - cached - written).coerceAtLeast(0.0)
        val output = usage.outputTokens.coerceAtLeast(0).toDouble()
        return (uncached * r.input + cached * r.cachedInput + written * r.cacheWrite + output * r.output) / 1_000_000.0
    }
}
