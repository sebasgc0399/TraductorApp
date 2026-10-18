package com.sebas.traductorapp.translate

import com.sebas.traductorapp.data.SpanishVariant
import com.sebas.traductorapp.model.GlossaryEntry
import com.sebas.traductorapp.model.SourceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val glossary = listOf(
        GlossaryEntry("Zed", "Zed"),
        GlossaryEntry("Azure Guild", "Gremio Celeste"),
        GlossaryEntry("Min-jun", "Min-jun"),
    )
    private val summary = "Min-jun es un cazador de rango E que despierta un sistema."

    @Test
    fun stablePartsAreByteIdenticalAcrossBatches() {
        val a = PromptBuilder.build(
            SpanishVariant.LATINO, glossary, summary,
            context = listOf("¡Corre!"),
            lines = listOf(SourceLine(1, "HELLO"), SourceLine(2, "WHO ARE YOU?")),
        )
        val b = PromptBuilder.build(
            SpanishVariant.LATINO, glossary.toList(), summary,
            context = listOf("¿Quién eres?", "Soy Zed.", "No te creo."),
            lines = listOf(SourceLine(7, "THE GATE IS OPENING!")),
        )
        assertEquals(a.instructions, b.instructions)
        assertEquals(a.seriesBlock, b.seriesBlock)
        assertTrue(a.stablePrefix.toByteArray(Charsets.UTF_8).contentEquals(b.stablePrefix.toByteArray(Charsets.UTF_8)))
        assertNotEquals(a.variablePart, b.variablePart)
    }

    @Test
    fun stablePrefixIsInstructionsPlusSeriesBlock() {
        for (variant in SpanishVariant.entries) {
            val p = PromptBuilder.build(variant, glossary, summary, emptyList(), listOf(SourceLine(1, "HI")))
            assertEquals(PromptBuilder.instructions(variant), p.instructions)
            assertEquals(p.instructions + p.seriesBlock, p.stablePrefix)
        }
    }

    @Test
    fun seriesBlockHasSummaryFirstAndGlossaryLastInGivenOrder() {
        val p = PromptBuilder.build(SpanishVariant.LATINO, glossary, summary, emptyList(), listOf(SourceLine(1, "HI")))
        assertEquals(
            "\n\n[SUMMARY]\n$summary\n\n[GLOSSARY]\nZed => Zed\nAzure Guild => Gremio Celeste\nMin-jun => Min-jun",
            p.seriesBlock,
        )
    }

    @Test
    fun appendedGlossaryTermKeepsThePreviousBlockAsPrefix() {
        val before = PromptBuilder.build(SpanishVariant.LATINO, glossary, summary, emptyList(), listOf(SourceLine(1, "HI")))
        val after = PromptBuilder.build(
            SpanishVariant.LATINO, glossary + GlossaryEntry("Shadow Step", "Paso Sombrío"), summary,
            emptyList(), listOf(SourceLine(1, "HI")),
        )
        assertEquals(before.instructions, after.instructions)
        assertTrue(after.seriesBlock.startsWith(before.seriesBlock))
        assertTrue(after.seriesBlock.endsWith("\nShadow Step => Paso Sombrío"))
    }

    @Test
    fun instructionsDoNotDependOnSeriesData() {
        val a = PromptBuilder.build(SpanishVariant.ESPANA, glossary, summary, emptyList(), listOf(SourceLine(1, "HI")))
        val b = PromptBuilder.build(SpanishVariant.ESPANA, emptyList(), "", listOf("Hola"), listOf(SourceLine(9, "BYE")))
        assertEquals(a.instructions, b.instructions)
        assertNotEquals(a.seriesBlock, b.seriesBlock)
    }

    @Test
    fun emptyGlossaryAndSummaryUsePlaceholder() {
        val p = PromptBuilder.build(SpanishVariant.ESPANA, emptyList(), "  ", emptyList(), listOf(SourceLine(1, "HI")))
        assertEquals("\n\n[SUMMARY]\n(vacío)\n\n[GLOSSARY]\n(vacío)", p.seriesBlock)
    }

    @Test
    fun variablePartFormat() {
        val p = PromptBuilder.build(
            SpanishVariant.LATINO, glossary, summary,
            context = listOf("¿Qué pasó?", "No lo sé."),
            lines = listOf(SourceLine(3, "WAIT,\nTHAT'S\r\nIMPOSSIBLE!"), SourceLine(1, "HUH?")),
        )
        assertEquals(
            "[CONTEXT]\n- ¿Qué pasó?\n- No lo sé.\n\n[TRANSLATE]\n3|WAIT, THAT'S IMPOSSIBLE!\n1|HUH?",
            p.variablePart,
        )
        assertEquals(listOf(3, 1), p.ids)
    }

    @Test
    fun emptyContextUsesStartMarker() {
        val p = PromptBuilder.build(SpanishVariant.LATINO, glossary, summary, emptyList(), listOf(SourceLine(5, "GO")))
        assertEquals("[CONTEXT]\n(inicio)\n\n[TRANSLATE]\n5|GO", p.variablePart)
    }

    @Test
    fun instructionsAloneAreLongEnoughForPromptCaching() {
        // ~4.1 caracteres por token: 4,500 caracteres ≈ 1,100 tokens (mínimo cacheable: 1,024). Hoy son ~1,380 tokens.
        for (variant in SpanishVariant.entries) {
            val instructions = PromptBuilder.instructions(variant)
            assertTrue("instrucciones de $variant: ${instructions.length} caracteres", instructions.length >= 4500)
            assertTrue(instructions.contains("SPANISH VARIANT"))
            // Describen el formato de salida actual y no traen datos de la serie.
            assertTrue(instructions.contains("""{"t":["1|"""))
            assertFalse(instructions.contains("[SUMMARY]\n"))
            assertFalse(instructions.contains("[GLOSSARY]\n"))
        }
        val latino = PromptBuilder.instructions(SpanishVariant.LATINO)
        val espana = PromptBuilder.instructions(SpanishVariant.ESPANA)
        assertNotEquals(latino, espana)
        assertTrue(latino.contains("ustedes"))
        assertTrue(espana.contains("vosotros"))
    }

    @Test
    fun maxOutputTokensIsClamped() {
        assertEquals(200, PromptBuilder.maxOutputTokensFor(emptyList()))
        val one = PromptBuilder.maxOutputTokensFor(listOf(SourceLine(1, "x".repeat(300))))
        // 300/3 * 1.6 + 40 + 150 = 350
        assertEquals(350, one)
        val huge = List(500) { SourceLine(it, "x".repeat(200)) }
        assertEquals(6000, PromptBuilder.maxOutputTokensFor(huge))
    }

    // ---------- resumen de la serie ----------

    @Test
    fun summaryInputKeepsTheLast80NonBlankLines() {
        val lines = List(100) { "L%03d".format(it + 1) } + listOf("  ", "")
        val input = PromptBuilder.summaryInput(
            SummaryRequest(SpanishVariant.LATINO, "Solo Leveling", "Jin-woo es un cazador débil.", lines),
        )
        assertTrue(input.startsWith("[SERIES]\nSolo Leveling\n\n[CURRENT SUMMARY]\nJin-woo es un cazador débil.\n\n[NEW LINES]\n"))
        val kept = input.substringAfter("[NEW LINES]\n").split("\n")
        assertEquals(80, kept.size)
        assertEquals("- L021", kept.first())
        assertEquals("- L100", kept.last())
    }

    @Test
    fun summaryInputUsesPlaceholdersAndOneLinePerEntry() {
        val input = PromptBuilder.summaryInput(
            SummaryRequest(SpanishVariant.ESPANA, " ", "", listOf("¿Qué\nhacéis?", "x".repeat(400))),
        )
        assertEquals(
            "[SERIES]\n(vacío)\n\n[CURRENT SUMMARY]\n(vacío)\n\n[NEW LINES]\n- ¿Qué hacéis?\n- ${"x".repeat(300)}",
            input,
        )
    }

    @Test
    fun summaryInstructionsDependOnVariant() {
        val latino = PromptBuilder.summaryInstructions(SpanishVariant.LATINO)
        val espana = PromptBuilder.summaryInstructions(SpanishVariant.ESPANA)
        assertNotEquals(latino, espana)
        assertTrue(latino.contains("120 words"))
        assertTrue(latino.contains("Latin American"))
        assertTrue(espana.contains("Spain Spanish"))
    }
}
