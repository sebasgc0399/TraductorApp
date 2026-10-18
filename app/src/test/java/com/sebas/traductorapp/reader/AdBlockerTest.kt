package com.sebas.traductorapp.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    @Test
    fun blocksKnownAdHostsAndSubdomains() {
        assertTrue(AdBlocker.isBlocked("doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("googleads.g.doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("pagead2.googlesyndication.com"))
        assertTrue(AdBlocker.isBlocked("adservice.google.com"))
        assertTrue(AdBlocker.isBlocked("a.exoclick.com"))
        assertTrue(AdBlocker.isBlocked("WWW.PopCash.Net"))
        assertTrue(AdBlocker.isBlocked("cdn.taboola.com."))
    }

    @Test
    fun neverBlocksEssentials() {
        assertFalse(AdBlocker.isBlocked("challenges.cloudflare.com"))
        assertFalse(AdBlocker.isBlocked("www.gstatic.com"))
        assertFalse(AdBlocker.isBlocked("www.google.com"))
        assertFalse(AdBlocker.isBlocked("vortexscans.org"))
        assertFalse(AdBlocker.isBlocked("storage.vortexscans.org"))
        assertFalse(AdBlocker.isBlocked("webtoon-phinf.pstatic.net"))
    }

    @Test
    fun matchesWholeLabelsOnly() {
        assertFalse(AdBlocker.isBlocked("notdoubleclick.net"))
        assertFalse(AdBlocker.isBlocked("doubleclick.net.example.com"))
    }

    @Test
    fun nullOrBlankIsAllowed() {
        assertFalse(AdBlocker.isBlocked(null))
        assertFalse(AdBlocker.isBlocked(""))
        assertFalse(AdBlocker.isBlocked("  "))
    }
}
