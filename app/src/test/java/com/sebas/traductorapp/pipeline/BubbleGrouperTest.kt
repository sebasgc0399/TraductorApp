package com.sebas.traductorapp.pipeline

import com.sebas.traductorapp.model.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleGrouperTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int, confidence: Float = 0.9f) =
        OcrLine(text, left, top, right, bottom, confidence)

    @Test
    fun stackedLinesOfOneBubbleMerge() {
        val first = line("HELLO THERE,", 100, 100, 300, 130, confidence = 0.8f)
        val second = line("HOW ARE YOU?", 110, 140, 290, 170, confidence = 0.6f)

        val groups = BubbleGrouper.group(listOf(second, first))

        assertEquals(1, groups.size)
        val group = groups[0]
        assertEquals("HELLO THERE, HOW ARE YOU?", group.text)
        assertEquals(100, group.left)
        assertEquals(100, group.top)
        assertEquals(300, group.right)
        assertEquals(170, group.bottom)
        assertEquals(30, group.lineHeight)
        assertEquals(0.7f, group.confidence, 1e-4f)
        assertEquals(listOf(first, second), group.lines)
    }

    @Test
    fun sideBySideBubblesStaySeparate() {
        val lines = listOf(
            line("I'M HERE", 50, 100, 250, 130),
            line("WAIT", 80, 140, 220, 170),
            line("WHO", 500, 100, 700, 130),
            line("ARE YOU", 520, 140, 680, 170),
        )

        val groups = BubbleGrouper.group(lines)

        assertEquals(listOf("I'M HERE WAIT", "WHO ARE YOU"), groups.map { it.text })
    }

    @Test
    fun bigSfxDoesNotMergeWithNearbyDialogue() {
        val dialogue = line("WHAT WAS THAT", 100, 100, 300, 130)
        val sfx = line("BOOM", 120, 140, 320, 230)

        val groups = BubbleGrouper.group(listOf(sfx, dialogue))

        assertEquals(listOf("WHAT WAS THAT", "BOOM"), groups.map { it.text })
        assertEquals(90, groups[1].lineHeight)
    }

    @Test
    fun bubblesInTheSameRowAreReadLeftToRight() {
        val right = line("RIGHT SIDE", 500, 100, 700, 130)
        val left = line("LEFT SIDE", 50, 112, 250, 142)
        val below = line("BELOW", 200, 600, 400, 630)

        val groups = BubbleGrouper.group(listOf(below, right, left))

        assertEquals(listOf("LEFT SIDE", "RIGHT SIDE", "BELOW"), groups.map { it.text })
    }

    @Test
    fun hyphenatedWordBrokenAcrossLinesIsJoined() {
        val groups = BubbleGrouper.group(
            listOf(
                line("THAT'S INCRED-", 100, 100, 300, 130),
                line("IBLE!", 150, 140, 250, 170),
            ),
        )

        assertEquals(listOf("THAT'S INCREDIBLE!"), groups.map { it.text })
    }

    @Test
    fun dashAfterSpaceIsNotTreatedAsHyphenation() {
        val groups = BubbleGrouper.group(
            listOf(
                line("WAIT -", 100, 100, 300, 130),
                line("NO!", 150, 140, 250, 170),
            ),
        )

        assertEquals(listOf("WAIT - NO!"), groups.map { it.text })
    }

    @Test
    fun emptyInputGivesNoGroups() {
        assertEquals(emptyList<LineGroup>(), BubbleGrouper.group(emptyList()))
    }
}
