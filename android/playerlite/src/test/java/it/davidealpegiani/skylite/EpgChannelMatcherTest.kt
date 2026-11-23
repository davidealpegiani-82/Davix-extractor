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


class EpgProgrammeMergerTest {
    @Test fun higherPrioritySourceWinsOverOverlappingFallback() {
        val selected = EpgProgrammeMerger.select(
            listOf(
                listOf(EpgProgramme(100, 200, "Sky programme")),
                listOf(EpgProgramme(100, 180, "Fallback programme"))
            ),
            now = 150
        )
        assertEquals("Sky programme", selected.current?.title)
    }

    @Test fun fallbackFillsAnActualGapBetweenPriorityProgrammes() {
        val selected = EpgProgrammeMerger.select(
            listOf(
                listOf(EpgProgramme(100, 150, "Sky one"), EpgProgramme(200, 250, "Sky two")),
                listOf(EpgProgramme(150, 200, "Fallback filler"))
            ),
            now = 160
        )
        assertEquals("Fallback filler", selected.current?.title)
        assertEquals("Sky two", selected.next?.title)
    }

    @Test fun nextProgrammeNeverStartsBeforeCurrentProgrammeEnds() {
        val selected = EpgProgrammeMerger.select(
            listOf(
                listOf(EpgProgramme(100, 220, "Preferred current")),
                listOf(EpgProgramme(150, 180, "Overlapping fallback"), EpgProgramme(220, 260, "True next"))
            ),
            now = 160
        )
        assertEquals("Preferred current", selected.current?.title)
        assertEquals("True next", selected.next?.title)
    }

    @Test fun invalidAndOverlappingIntervalsAreIgnored() {
        val selected = EpgProgrammeMerger.select(
            listOf(listOf(
                EpgProgramme(100, 100, "Invalid"),
                EpgProgramme(100, 200, "Current"),
                EpgProgramme(180, 240, "Overlap"),
                EpgProgramme(240, 280, "Next")
            )),
            now = 150
        )
        assertEquals("Current", selected.current?.title)
        assertEquals("Next", selected.next?.title)
    }

    @Test fun futureScheduleIsReturnedWhenNoProgrammeIsOnAir() {
        val selected = EpgProgrammeMerger.select(
            listOf(listOf(EpgProgramme(200, 250, "Upcoming"))),
            now = 150
        )
        assertNull(selected.current)
        assertEquals("Upcoming", selected.next?.title)
    }
}
