package it.davidealpegiani.skyunoextractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItalianLiveChannelFilterTest {
    @Test fun acceptsKnownItalianBroadcasters() {
        assertTrue(ItalianLiveChannelFilter.isItalian("Rai 1 HD", "Entertainment"))
        assertTrue(ItalianLiveChannelFilter.isItalian("Sky Uno", "Intrattenimento"))
        assertTrue(ItalianLiveChannelFilter.isItalian("Canale 5", "General"))
        assertTrue(ItalianLiveChannelFilter.isItalian("Telelombardia", "Local"))
    }

    @Test fun acceptsExplicitItalianCategoryOrMetadata() {
        assertTrue(ItalianLiveChannelFilter.isItalian("Unknown Channel", "🇮🇹 Italia"))
        assertTrue(ItalianLiveChannelFilter.isItalian("Unknown Channel", "General", listOf("it")))
        assertTrue(ItalianLiveChannelFilter.isItalian("Unknown Channel", "General", listOf("Italian")))
    }

    @Test fun excludesForeignAndUnidentifiedChannels() {
        assertFalse(ItalianLiveChannelFilter.isItalian("BBC One", "United Kingdom"))
        assertFalse(ItalianLiveChannelFilter.isItalian("CNN International", "News"))
        assertFalse(ItalianLiveChannelFilter.isItalian("Unknown Channel", "General"))
    }
}
