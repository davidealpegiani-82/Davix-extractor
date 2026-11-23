package it.davidealpegiani.skyunoextractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamStreamScannerTest {
    @Test
    fun cacheKeyIsStableAndDoesNotExposeStreamUrl() {
        val url = "https://provider.example/live/user/secret/123.ts"
        val first = XtreamStreamScanner.cacheKey(url)
        val second = XtreamStreamScanner.cacheKey(url)

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(url, first)
        assertNotEquals("secret", first)
    }

    @Test
    fun differentStreamUrlsHaveDifferentCacheKeys() {
        val first = XtreamStreamScanner.cacheKey("https://provider.example/live/a/b/1.ts")
        val second = XtreamStreamScanner.cacheKey("https://provider.example/live/a/b/2.ts")
        assertNotEquals(first, second)
    }
}
