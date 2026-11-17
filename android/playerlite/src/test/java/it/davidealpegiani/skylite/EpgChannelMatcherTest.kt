package it.davidealpegiani.skylite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgChannelMatcherTest {
    @Test fun exactTvgIdHasPriority() {
        assertEquals("sky.uno", EpgChannelMatcher.findMatch("SKY.UNO", "Other", "Other", mapOf("sky.uno" to listOf("Sky Uno"))))
    }
    @Test fun exactNameIgnoresQualitySuffix() {
        assertEquals("rai1", EpgChannelMatcher.findMatch("", "Rai 1 HD", "Rai 1", mapOf("rai1" to listOf("RAI 1"))))
    }
    @Test fun ambiguousExactNameIsRejected() {
        assertNull(EpgChannelMatcher.findMatch("", "Cinema Uno", "Cinema Uno", mapOf("a" to listOf("Cinema Uno"), "b" to listOf("Cinema Uno"))))
    }
    @Test fun closeFuzzyCandidatesAreRejected() {
        assertNull(EpgChannelMatcher.findMatch("", "National Geographic", "National Geographic", mapOf("a" to listOf("National Geographix"), "b" to listOf("National Geographiy"))))
    }
    @Test fun highConfidenceUniqueFuzzyCandidateIsAccepted() {
        assertEquals("natgeo", EpgChannelMatcher.findMatch("", "National Geographic", "National Geographic", mapOf("natgeo" to listOf("National Geographik"))))
    }
    @Test fun cachedAssociationSurvivesNameDrift() {
        assertEquals("stable-id", EpgChannelMatcher.findMatch("", "Renamed Channel", "Renamed Channel", mapOf("stable-id" to listOf("Old EPG Title")), cachedId = "stable-id"))
    }
}
