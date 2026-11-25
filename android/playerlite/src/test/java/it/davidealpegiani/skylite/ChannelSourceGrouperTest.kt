package it.davidealpegiani.skylite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSourceGrouperTest {
    @Test fun qualityVariantsOfSameChannelAreGroupedEvenAcrossDifferentTvgIds() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno HD", "https://a.example/live/skyuno.m3u8", tvgId = "provider.one"),
                CloudChannel("Sky Uno FHD", "https://b.example/live/skyuno-fhd.m3u8", tvgId = "another.id"),
                CloudChannel("Sky Uno 4K", "https://c.example/live/skyuno-4k.m3u8")
            )
        )
        assertEquals(1, groups.size)
        assertEquals("Sky Uno", groups.single().name)
        assertEquals(3, groups.single().sources.size)
    }

    @Test fun sameTvgIdNeverMergesDifferentChannelNames() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno HD", "https://a.example/live/uno.m3u8", tvgId = "reused-id"),
                CloudChannel("Sky Sport Uno HD", "https://b.example/live/sportuno.m3u8", tvgId = "reused-id"),
                CloudChannel("Sky Cinema Uno FHD", "https://c.example/live/cinemauno.m3u8", tvgId = "reused-id")
            )
        )
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.sources.size == 1 })
    }

    @Test fun plusOneAndNumberedChannelsRemainDistinct() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno", "https://a.example/live/uno.m3u8"),
                CloudChannel("Sky Uno +1", "https://b.example/live/uno-plus1.m3u8"),
                CloudChannel("Sky Uno 2", "https://c.example/live/uno2.m3u8")
            )
        )
        assertEquals(3, groups.size)
    }

    @Test fun similarButDistinctChannelNamesStaySeparate() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno", "https://a.example/live/uno.m3u8"),
                CloudChannel("Sky Sport Uno", "https://b.example/live/sportuno.m3u8"),
                CloudChannel("Sky Cinema Uno", "https://c.example/live/cinemauno.m3u8"),
                CloudChannel("Sky Uno Plus", "https://d.example/live/unoplus.m3u8")
            )
        )
        assertEquals(4, groups.size)
    }

    @Test fun providerCategoryDifferencesDoNotSplitSameLiveChannel() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno HD", "https://a.example/live/skyuno.m3u8", category = "Intrattenimento"),
                CloudChannel("Sky Uno 4K", "https://b.example/live/skyuno4k.m3u8", category = "Non classificati")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().sources.size)
    }

    @Test fun explicitVodCategorySeparatesSameNameFromLiveChannel() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Serie HD", "https://a.example/live/skyserie.m3u8", category = "LIVE > Intrattenimento"),
                CloudChannel("Sky Serie FHD", "https://b.example/vod/skyserie/episode.m3u8", category = "VOD > SERIE")
            )
        )
        assertEquals(2, groups.size)
        assertEquals(setOf("live", "vod"), groups.map { it.key.substringBefore('|') }.toSet())
    }

    @Test fun skySerieAndSkyCinemaBrandsRemainLive() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Serie HD", "https://a.example/live/skyserie.m3u8"),
                CloudChannel("Sky Cinema Uno FHD", "https://b.example/live/cinema.m3u8")
            )
        )
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.key.startsWith("live|") })
    }

    @Test fun episodeEvidenceSeparatesVodFromGenericLiveLabel() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("The Example Show S01E02", "https://cdn.example/file.m3u8", category = "LIVE > Intrattenimento")
            )
        )
        assertEquals("vod", groups.single().key.substringBefore('|'))
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

    @Test fun duplicateUrlIsNotListedTwice() {
        val groups = ChannelSourceGrouper.group(
            listOf(
                CloudChannel("Sky Uno HD", "https://a.example/live/uno.m3u8"),
                CloudChannel("Sky Uno FHD", "https://a.example/live/uno.m3u8")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().sources.size)
    }
}
