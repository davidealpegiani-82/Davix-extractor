package it.davidealpegiani.skylite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MandraKodiSourceAdapterTest {
    private val adapter = MandraKodiSourceAdapter()

    @Test
    fun parsesM3uMetadataAndStableIds() {
        val input = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news.it" tvg-name="News One" tvg-logo="https://img.example/logo.png" group-title="News",News One
            https://stream.example/live/news.m3u8
        """.trimIndent()
        val first = adapter.parse(input)
        val second = adapter.parse(input)
        assertEquals(1, first.size)
        assertEquals("News One", first.first().name)
        assertEquals("News", first.first().category)
        assertEquals("news.it", first.first().tvgId)
        assertEquals("https://img.example/logo.png", first.first().logoUrl)
        assertEquals(first.first().stableId, second.first().stableId)
    }

    @Test
    fun parsesJsonChannelsAndGroups() {
        val input = """{"channels":[{"name":"Channel A","url":"https://stream.example/a.m3u8","category":"General"}],"groups":[{"name":"Cinema","stations":[{"name":"Channel B","url":"https://stream.example/b.mpd"}]}]}"""
        val channels = adapter.parse(input)
        assertEquals(2, channels.size)
        assertTrue(channels.any { it.name == "Channel A" && it.category == "General" })
        assertTrue(channels.any { it.name == "Channel B" && it.category == "Cinema" })
    }

    @Test
    fun ignoresKodiActionsAndNonDirectLinks() {
        val input = """{"items":[{"title":"Direct stream","link":"https://stream.example/live.m3u8"},{"title":"Resolver action","myresolve":"https://site.example/watch/123"},{"title":"Folder","externallink":"https://site.example/menu.json"},{"title":"Local ace","link":"http://127.0.0.1:6878/ace/getstream?id=1"}]}"""
        val channels = adapter.parse(input)
        assertEquals(1, channels.size)
        assertEquals("Direct stream", channels.single().name)
    }

    @Test
    fun rejectsNonHttpCatalogUrl() {
        val error = runCatching {
            adapter.load(MandraKodiSourceConfig(catalogUrl = "file:///etc/passwd"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
