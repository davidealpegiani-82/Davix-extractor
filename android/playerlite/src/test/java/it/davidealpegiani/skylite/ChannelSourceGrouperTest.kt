package it.davidealpegiani.skylite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSourceGrouperTest {
    @Test fun skySerieBrandRemainsALiveChannel() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Serie HD", "https://a.example/live/skyserie.m3u8"),
                CloudChannel("Sky Serie 4K", "https://b.example/live/skyserie4k.m3u8")
            )
        )
        assertEquals(1, groups.size)
        assertEquals("live", groups.single().key.substringBefore('|'))
        assertEquals(2, groups.single().sources.size)
    }

    @Test fun skyCinemaBrandIsNotMistakenForVod() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Cinema Uno HD", "https://a.example/live/cinema.m3u8"),
                CloudChannel("Sky Cinema Uno FHD", "https://b.example/live/cinema-fhd.m3u8")
            )
        )
        assertEquals(1, groups.size)
        assertEquals("live", groups.single().key.substringBefore('|'))
    }

    @Test fun explicitVodCategorySeparatesSameTvgIdFromLiveChannel() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Serie", "https://a.example/live/skyserie.m3u8", tvgId = "sky.serie", category = "LIVE > Intrattenimento"),
                CloudChannel("Sky Serie", "https://b.example/vod/skyserie/episode.m3u8", tvgId = "sky.serie", category = "VOD > SERIE")
            )
        )
        assertEquals(2, groups.size)
        assertEquals(setOf("live", "vod"), groups.map { it.key.substringBefore('|') }.toSet())
    }

    @Test fun episodeEvidenceOverridesGenericLiveProviderLabel() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("The Example Show S01E02", "https://cdn.example/file.m3u8", category = "LIVE > Intrattenimento")
            )
        )
        assertEquals("vod", groups.single().key.substringBefore('|'))
    }

    @Test fun differentProviderCategoriesDoNotSplitSameLiveChannel() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno HD", "https://a.example/live/skyuno.m3u8", category = "Intrattenimento"),
                CloudChannel("Sky Uno 4K", "https://b.example/live/skyuno4k.m3u8", category = "Non classificati")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().sources.size)
    }

    @Test fun unnamedChannelsWithDifferentUrlsAreNotMerged() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("", "https://a.example/live/one.m3u8"),
                CloudChannel("", "https://b.example/live/two.m3u8")
            )
        )
        assertEquals(2, groups.size)
    }

    @Test fun similarButDistinctChannelNamesStaySeparate() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno", "https://a.example/live/uno.m3u8"),
                CloudChannel("Sky Sport Uno", "https://b.example/live/sportuno.m3u8")
            )
        )
        assertEquals(2, groups.size)
        assertTrue(groups.none { it.sources.size > 1 })
    }
}
