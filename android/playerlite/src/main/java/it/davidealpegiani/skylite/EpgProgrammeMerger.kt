package it.davidealpegiani.skylite

/** A single programme interval from an XMLTV source. */
internal data class EpgProgramme(val start: Long, val stop: Long, val title: String)

/** Current/next selection after merging source schedules in priority order. */
internal data class EpgProgrammeSelection(
    val current: EpgProgramme?,
    val next: EpgProgramme?
)

/**
 * Merges schedules without allowing lower-priority sources to overwrite or
 * overlap an interval already supplied by a higher-priority source.
 */
internal object EpgProgrammeMerger {
    fun select(schedulesByPriority: List<List<EpgProgramme>>, now: Long): EpgProgrammeSelection {
        val accepted = mutableListOf<EpgProgramme>()
        schedulesByPriority.forEach { schedule ->
            schedule.asSequence()
                .filter { it.start < it.stop }
                .sortedWith(compareBy<EpgProgramme> { it.start }.thenBy { it.stop })
                .forEach { candidate ->
                    val overlapsAccepted = accepted.any { existing ->
                        candidate.start < existing.stop && candidate.stop > existing.start
                    }
                    if (!overlapsAccepted) accepted.add(candidate)
                }
        }

        val ordered = accepted.sortedBy { it.start }
        val current = ordered.firstOrNull { it.start <= now && it.stop > now }
        val next = if (current != null) {
            ordered.firstOrNull { it.start >= current.stop }
        } else {
            ordered.firstOrNull { it.start > now }
        }
        return EpgProgrammeSelection(current, next)
    }
}
