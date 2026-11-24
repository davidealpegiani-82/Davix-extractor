package it.davidealpegiani.skylite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPlaylistParserTest {
    @Test fun parsesMetadataAndCategories() {
        val input = """#EXTM3U
#EXTINF:-1 tvg-id="rai1.it" tvg-name="Rai 1" tvg-logo="https://example.org/logo.png" group-title="Intrattenimento",Rai 1 HD
https://example.org/rai1.m3u8
#EXTINF:-1 group-title="Sport",Canale Sport
https://example.org/sport.m3u8
"""
        val channels = LocalPlaylistParser.parse(input)
        assertEquals(2, channels.size)
        assertEquals("Rai 1 HD", channels[0].name)
        assertEquals("rai1.it", channels[0].tvgId)
        assertEquals("Intrattenimento", channels[0].category)
        assertEquals("Sport", channels[1].category)
    }

    @Test fun removesExactDuplicatesOnly() {
        val input = """#EXTM3U
#EXTINF:-1 group-title="Sport",Canale A
https://example.org/a.m3u8
#EXTINF:-1 group-title="Sport",Canale A
https://example.org/a.m3u8
#EXTINF:-1 group-title="Sport",Canale A
https://example.org/b.m3u8
"""
        assertEquals(2, LocalPlaylistParser.parse(input).size)
    }

    @Test fun rejectsEmptyAndNonHttpPlaylists() {
        assertThrows(IllegalArgumentException::class.java) { LocalPlaylistParser.parse("#EXTM3U\n") }
        assertThrows(IllegalArgumentException::class.java) {
            LocalPlaylistParser.parse("#EXTM3U\n#EXTINF:-1,Local\nfile:///tmp/a.m3u8")
        }
    }
}
