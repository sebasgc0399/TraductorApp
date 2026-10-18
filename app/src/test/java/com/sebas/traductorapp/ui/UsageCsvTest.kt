package com.sebas.traductorapp.ui

import com.sebas.traductorapp.data.UsageLogEntity
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCsvTest {

    private val header =
        "timestamp_iso,series,chapter,model,bubbles,input_tokens,cached_tokens," +
            "cache_write_tokens,output_tokens,reasoning_tokens,latency_ms,cost_usd"

    private fun entry(
        seriesKey: String = "vortexscans.org/series/solo-leveling",
        chapter: String? = "24",
        model: String = "gpt-6-luna",
        costUsd: Double = 0.0021,
    ) = UsageLogEntity(
        id = 1,
        timestamp = Instant.parse("2026-10-18T12:34:56Z").toEpochMilli(),
        seriesKey = seriesKey,
        chapter = chapter,
        model = model,
        bubbles = 12,
        inputTokens = 1500,
        cachedTokens = 1024,
        cacheWriteTokens = 0,
        outputTokens = 300,
        reasoningTokens = 0,
        latencyMs = 850,
        costUsd = costUsd,
    )

    private fun lines(csv: String): List<String> = csv.trimEnd('\n').split('\n')

    @Test
    fun emptyListProducesOnlyHeader() {
        assertEquals(listOf(header), lines(UsageCsv.build(emptyList())))
        assertEquals(header, UsageCsv.HEADER)
    }

    @Test
    fun rowHasIsoUtcTimestampAndAllColumns() {
        val rows = lines(UsageCsv.build(listOf(entry())))
        assertEquals(2, rows.size)
        assertEquals(header, rows[0])
        assertEquals(
            "2026-10-18T12:34:56Z,vortexscans.org/series/solo-leveling,24,gpt-6-luna," +
                "12,1500,1024,0,300,0,850,0.002100",
            rows[1],
        )
    }

    @Test
    fun nullChapterIsEmptyField() {
        val row = lines(UsageCsv.build(listOf(entry(chapter = null))))[1]
        assertTrue(row.startsWith("2026-10-18T12:34:56Z,vortexscans.org/series/solo-leveling,,gpt-6-luna,"))
    }

    @Test
    fun quotesFieldsWithCommaQuoteOrNewline() {
        val csv = UsageCsv.build(
            listOf(entry(seriesKey = "a,b", chapter = "dice \"hola\"", model = "x\ny")),
        )
        assertTrue(csv.contains(",\"a,b\",\"dice \"\"hola\"\"\",\"x\ny\","))
    }

    @Test
    fun escapeLeavesPlainFieldsUntouched() {
        assertEquals("gpt-6-luna", UsageCsv.escape("gpt-6-luna"))
        assertEquals("\"a\rb\"", UsageCsv.escape("a\rb"))
        assertEquals("\"\"\"\"", UsageCsv.escape("\""))
    }

    @Test
    fun costHasSixDecimalsWithDotRegardlessOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val rows = lines(UsageCsv.build(listOf(entry(costUsd = 1.5), entry(costUsd = 0.0000004))))
            assertTrue(rows[1].endsWith(",1.500000"))
            assertTrue(rows[2].endsWith(",0.000000"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
