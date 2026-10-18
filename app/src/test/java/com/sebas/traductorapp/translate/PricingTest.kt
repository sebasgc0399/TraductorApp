package com.sebas.traductorapp.translate

import com.sebas.traductorapp.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class PricingTest {
    private val eps = 1e-12

    @Test
    fun gpt6LunaCostWithCacheReadsAndWrites() {
        val usage = TokenUsage(
            inputTokens = 10_000,
            cachedTokens = 6_000,
            cacheWriteTokens = 1_500,
            outputTokens = 800,
            reasoningTokens = 0,
        )
        // 2,500 × 0.10 + 6,000 × 0.01 + 1,500 × 0.125 + 800 × 0.50 = 897.5 → /1e6
        assertEquals(0.0008975, Pricing.costUsd("gpt-6-luna", usage), eps)
    }

    @Test
    fun snapshotNamesResolveToBaseModel() {
        assertEquals(Pricing.rates("gpt-6-luna"), Pricing.rates("gpt-6-luna-2026-09-15"))
        assertEquals(Pricing.rates("gpt-6-luna"), Pricing.rates("  GPT-6-Luna  "))
        assertEquals(0.20, Pricing.rates("gpt-5.6-luna-2026-05-01").input, eps)
        assertEquals(0.75, Pricing.rates("gpt-5.4-mini").input, eps)
        assertEquals(0.25, Pricing.rates("gpt-5-mini-2025-08-07").input, eps)
        assertEquals(0.05, Pricing.rates("gpt-5-nano").input, eps)
    }

    @Test
    fun modelsWithoutWriteSurchargeBillWritesAsInput() {
        assertEquals(0.75, Pricing.rates("gpt-5.4-mini").cacheWrite, eps)
        assertEquals(0.25, Pricing.rates("gpt-5-mini").cacheWrite, eps)
        assertEquals(0.125, Pricing.rates("gpt-6-luna").cacheWrite, eps)
    }

    @Test
    fun unknownModelFallsBackToGpt6Luna() {
        assertEquals(Pricing.rates("gpt-6-luna"), Pricing.rates("some-future-model"))
    }

    @Test
    fun uncachedInputNeverNegative() {
        val usage = TokenUsage(inputTokens = 100, cachedTokens = 100, cacheWriteTokens = 50, outputTokens = 0, reasoningTokens = 0)
        // Solo cuentan caché leído y escrito: 100 × 0.01 + 50 × 0.125 = 7.25
        assertEquals(7.25e-6, Pricing.costUsd("gpt-6-luna", usage), eps)
    }
}
