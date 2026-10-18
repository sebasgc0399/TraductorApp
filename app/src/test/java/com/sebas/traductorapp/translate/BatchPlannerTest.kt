package com.sebas.traductorapp.translate

import com.sebas.traductorapp.translate.BatchPlanner.Candidate
import com.sebas.traductorapp.translate.BatchPlanner.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPlannerTest {

    private fun cands(n: Int, urgentFirst: Int = 0, chapter: String? = "24", keyOf: (Int) -> String = { "k$it" }) =
        (0 until n).map { Candidate(item = it, key = keyOf(it), urgent = it < urgentFirst, chapter = chapter) }

    @Test
    fun emptyIsIdle() {
        assertEquals(Decision.Idle, BatchPlanner.plan(emptyList<Candidate<Int>>(), ocrBusy = true, waitedMs = 0))
    }

    @Test
    fun urgentGoesImmediatelyAndIsCapped() {
        val d = BatchPlanner.plan(cands(30, urgentFirst = 3), ocrBusy = true, waitedMs = 0)
        d as Decision.Send
        assertTrue(d.urgent)
        assertEquals(BatchPlanner.URGENT_MAX, d.groups.size)
        // Los urgentes van primero.
        assertEquals(listOf(0, 1, 2), d.groups.take(3).map { it.single() })
    }

    @Test
    fun smallPrefetchWaitsWhileOcrIsBusy() {
        val d = BatchPlanner.plan(cands(2), ocrBusy = true, waitedMs = 300)
        assertTrue(d is Decision.Wait)
        assertEquals(2200L, (d as Decision.Wait).millis)
    }

    @Test
    fun smallPrefetchGoesWhenOcrIsDone() {
        val d = BatchPlanner.plan(cands(7), ocrBusy = false, waitedMs = 0)
        d as Decision.Send
        assertEquals(7, d.groups.size)
    }

    @Test
    fun prefetchGoesAfterMaxWait() {
        val d = BatchPlanner.plan(cands(3), ocrBusy = true, waitedMs = BatchPlanner.MAX_WAIT_MS)
        assertTrue(d is Decision.Send)
    }

    @Test
    fun bigPrefetchSendsTargetOrMore() {
        val d = BatchPlanner.plan(cands(50), ocrBusy = true, waitedMs = 0)
        d as Decision.Send
        assertEquals(40, d.groups.size)
    }

    @Test
    fun duplicatesAreSentOnce() {
        val d = BatchPlanner.plan(cands(6, keyOf = { if (it % 2 == 0) "same" else "k$it" }), ocrBusy = false, waitedMs = 0)
        d as Decision.Send
        assertEquals(4, d.groups.size)
        assertEquals(listOf(0, 2, 4), d.groups.first())
    }

    @Test
    fun neverMixesChapters() {
        val current = cands(3, chapter = "24")
        val next = cands(10, chapter = "25").map { it.copy(item = it.item + 100, key = "n${it.key}") }
        val d = BatchPlanner.plan(current + next, ocrBusy = false, waitedMs = 0)
        d as Decision.Send
        assertTrue(d.groups.flatten().all { it < 100 })
    }

    @Test
    fun limitAfterErrorsIsRespected() {
        val d = BatchPlanner.plan(cands(30), ocrBusy = false, waitedMs = 0, limit = 9)
        d as Decision.Send
        assertEquals(9, d.groups.size)
    }

    @Test
    fun contextShrinksWithBatchSize() {
        assertEquals(15, BatchPlanner.contextLinesFor(2))
        assertEquals(10, BatchPlanner.contextLinesFor(12))
        assertEquals(6, BatchPlanner.contextLinesFor(30))
    }
}
