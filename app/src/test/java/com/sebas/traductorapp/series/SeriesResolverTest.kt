package com.sebas.traductorapp.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesResolverTest {

    private val vortexSlug = "i-became-a-transcendent-hunter-with-100000x-exp"

    @Test
    fun rotatingHashInSlugIsDroppedFromKey() {
        val a = SeriesResolver.resolve("https://asuracomic.net/series/solo-hunter-a1b2c3d4/chapter/12", null)
        val b = SeriesResolver.resolve("https://asuracomic.net/series/solo-hunter-9f8e7d6c/chapter/13", null)
        assertEquals("asuracomic.net/series/solo-hunter", a.seriesKey)
        assertEquals(a.seriesKey, b.seriesKey)
        assertEquals("12", a.chapter)
    }

    @Test
    fun vortexChapter() {
        val info = SeriesResolver.resolve("https://vortexscans.org/series/$vortexSlug/chapter-24", null)
        assertEquals("vortexscans.org/series/$vortexSlug", info.seriesKey)
        assertEquals("24", info.chapter)
        assertEquals("I Became A Transcendent Hunter With 100000x Exp", info.seriesTitle)
    }

    @Test
    fun vortexWithWwwTrailingSlashAndTitleGivesSameKey() {
        val info = SeriesResolver.resolve(
            "https://www.vortexscans.org/series/$vortexSlug/chapter-25/",
            "I Became a Transcendent Hunter with 100000x EXP Chapter 25 - Vortex Scans",
        )
        assertEquals("vortexscans.org/series/$vortexSlug", info.seriesKey)
        assertEquals("25", info.chapter)
    }

    @Test
    fun webtoonsViewer() {
        val info = SeriesResolver.resolve(
            "https://www.webtoons.com/en/fantasy/tower-of-god/season-3-ep-133/viewer?title_no=95&episode_no=550",
            "Tower of God - Season 3 Ep. 133 | WEBTOON",
        )
        assertEquals("webtoons.com/title/95", info.seriesKey)
        assertEquals("550", info.chapter)
        assertEquals("Tower Of God", info.seriesTitle)
    }

    @Test
    fun webtoonsMobileWithoutEpisodeNoUsesPath() {
        val info = SeriesResolver.resolve(
            "https://m.webtoons.com/en/romance/lore-olympus/episode-12/viewer?title_no=1320",
            null,
        )
        assertEquals("webtoons.com/title/1320", info.seriesKey)
        assertEquals("12", info.chapter)
        assertEquals("Lore Olympus", info.seriesTitle)
    }

    @Test
    fun mangadexChapterUsesPageTitle() {
        val info = SeriesResolver.resolve(
            "https://mangadex.org/chapter/0f5b0e38-2c5b-4b8a-9a8e-3f2d5c1a7b90/1",
            "Ch. 24 - Solo Leveling - MangaDex",
        )
        assertEquals("mangadex.org/t/solo-leveling", info.seriesKey)
        assertEquals("24", info.chapter)
        assertEquals("Solo Leveling", info.seriesTitle)
    }

    @Test
    fun mangadexVolumeAndDecimalChapter() {
        val info = SeriesResolver.resolve(
            "https://mangadex.org/chapter/0f5b0e38-2c5b-4b8a-9a8e-3f2d5c1a7b90",
            "Vol. 3 Ch. 24.5 - Kaguya-sama: Love Is War - MangaDex",
        )
        assertEquals("mangadex.org/t/kaguya-sama-love-is-war", info.seriesKey)
        assertEquals("24.5", info.chapter)
        assertEquals("Kaguya-sama: Love Is War", info.seriesTitle)
    }

    @Test
    fun chapterIdWithSiteSuffixInTitle() {
        val info = SeriesResolver.resolve("https://example.org/chapter/abc-123-def", "Some Series Chapter 24 | Site")
        assertEquals("example.org/t/some-series", info.seriesKey)
        assertEquals("24", info.chapter)
        assertEquals("Some Series", info.seriesTitle)
    }

    @Test
    fun mangadexWithoutTitleIsUnknown() {
        val info = SeriesResolver.resolve("https://mangadex.org/chapter/0f5b0e38-2c5b-4b8a-9a8e-3f2d5c1a7b90/1", null)
        assertEquals("mangadex.org/unknown", info.seriesKey)
        assertNull(info.chapter)
    }

    @Test
    fun genericMangaDecimalChapter() {
        val info = SeriesResolver.resolve("https://example.com/manga/foo-bar/chapter-12.5/", null)
        assertEquals("example.com/manga/foo-bar", info.seriesKey)
        assertEquals("12.5", info.chapter)
        assertEquals("Foo Bar", info.seriesTitle)
    }

    @Test
    fun madaraDashDecimalChapter() {
        val info = SeriesResolver.resolve("https://example.com/manga/foo-bar/chapter-12-5/", null)
        assertEquals("example.com/manga/foo-bar", info.seriesKey)
        assertEquals("12.5", info.chapter)
    }

    @Test
    fun comicsShortChapterMarker() {
        val info = SeriesResolver.resolve("https://example.com/comics/foo-bar/ch-7", null)
        assertEquals("example.com/comics/foo-bar", info.seriesKey)
        assertEquals("7", info.chapter)
    }

    @Test
    fun chapterAsSeparateSegmentAndHashedSlug() {
        val info = SeriesResolver.resolve("https://asuracomic.net/series/omniscient-readers-viewpoint-6f3c2b1a/chapter/24", null)
        assertEquals("asuracomic.net/series/omniscient-readers-viewpoint", info.seriesKey)
        assertEquals("24", info.chapter)
        assertEquals("Omniscient Readers Viewpoint", info.seriesTitle)
    }

    @Test
    fun inlineChapterInSingleSegment() {
        val info = SeriesResolver.resolve("https://example.net/foo-bar-chapter-7/", null)
        assertEquals("example.net/foo-bar", info.seriesKey)
        assertEquals("7", info.chapter)
        assertEquals("Foo Bar", info.seriesTitle)
    }

    @Test
    fun numericLastSegment() {
        val info = SeriesResolver.resolve("https://example.com/read/foo-bar/024", null)
        assertEquals("example.com/read/foo-bar", info.seriesKey)
        assertEquals("24", info.chapter)
    }

    @Test
    fun seriesPageWithoutChapter() {
        val info = SeriesResolver.resolve("https://vortexscans.org/series/$vortexSlug", "Some title - Vortex Scans")
        assertEquals("vortexscans.org/series/$vortexSlug", info.seriesKey)
        assertNull(info.chapter)
        assertEquals("I Became A Transcendent Hunter With 100000x Exp", info.seriesTitle)
    }

    @Test
    fun homePageWithoutChapter() {
        val info = SeriesResolver.resolve("https://example.com/", null)
        assertEquals("example.com", info.seriesKey)
        assertNull(info.chapter)
    }
}
