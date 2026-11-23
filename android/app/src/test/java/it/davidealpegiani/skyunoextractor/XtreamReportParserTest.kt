package it.davidealpegiani.skyunoextractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamReportParserTest {
    @Test
    fun parsesCredentialsServerAndCategorySectionsWithoutLeakingSecrets() {
        val report = """
            Server: http://provider.example:8080
            Username: sample-user
            Password: sample-password
            EPG URL: http://provider.example:8080/xmltv.php?username=sample-user&password=sample-password

            Live Categories:
            - News
            - Sport

            VOD Categories:
            - Cinema

            Series Categories:
            - Drama
        """.trimIndent()

        val source = XtreamReportParser.parse(report)

        assertEquals("http://provider.example:8080", source.baseUrl)
        assertEquals("sample-user", source.username)
        assertEquals("sample-password", source.password)
        assertEquals(listOf("News", "Sport"), source.liveCategories)
        assertEquals(listOf("Cinema"), source.vodCategories)
        assertEquals(listOf("Drama"), source.seriesCategories)
        assertTrue(source.redactedSummary().contains("[REDACTED]"))
        assertTrue(!source.redactedSummary().contains("sample-password"))
    }

    @Test
    fun readsCredentialsFromStandardXtreamPlaylistUrl() {
        val report = """
            Playlist: http://provider.example:80/get.php?username=sample-user&password=sample-password&type=m3u_plus
        """.trimIndent()

        val source = XtreamReportParser.parse(report)

        assertEquals("http://provider.example:80", source.baseUrl)
        assertEquals("sample-user", source.username)
        assertEquals("sample-password", source.password)
    }
}
