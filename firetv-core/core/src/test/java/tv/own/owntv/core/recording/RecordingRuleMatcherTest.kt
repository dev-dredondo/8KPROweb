package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus

/**
 * "Record every showing of this title on this channel" — the matching and the de-duplication.
 *
 * The folding cases are the ones a real XMLTV feed produces in a single week from one provider, not
 * invented awkwardness: the same programme really does arrive as `The News`, `The News (HD)` and
 * `The News - Episode 4`.
 */
class RecordingRuleMatcherTest {

    private val hour = 3_600_000L
    private val nine = 1_757_710_800_000L

    // --- Folding ---

    @Test
    fun `case and spacing do not make a different programme`() {
        assertEquals(RecordingRuleMatcher.fold("The News"), RecordingRuleMatcher.fold("THE NEWS"))
        assertEquals(RecordingRuleMatcher.fold("The News"), RecordingRuleMatcher.fold("  the   news  "))
    }

    @Test
    fun `a quality or repeat tag describes the showing, not the programme`() {
        assertTrue(RecordingRuleMatcher.sameProgramme("The News", "The News (HD)"))
        assertTrue(RecordingRuleMatcher.sameProgramme("The News", "The News [Repeat]"))
        // Providers stack them.
        assertTrue(RecordingRuleMatcher.sameProgramme("The News", "The News (HD) [S2 E4]"))
    }

    @Test
    fun `an episode suffix does not make each episode its own programme`() {
        assertTrue(RecordingRuleMatcher.sameProgramme("The News", "The News - Episode 4"))
        assertTrue(RecordingRuleMatcher.sameProgramme("The News", "The News: Part 2"))
        assertTrue(RecordingRuleMatcher.sameProgramme("The News", "The News – Ep. 12"))
    }

    @Test
    fun `an apostrophe is not a difference, whichever kind the feed used`() {
        assertTrue(RecordingRuleMatcher.sameProgramme("Marvel's What If…?", "Marvels What If"))
        // Straight and curly, which one feed will happily mix inside a single week.
        assertTrue(RecordingRuleMatcher.sameProgramme("Marvel's What If", "Marvel’s What If"))
    }

    @Test
    fun `other punctuation separates words rather than vanishing`() {
        // `Law & Order` must fold to "law order", not "laworder" — otherwise titles that differ only
        // by a joining word would start colliding.
        assertFalse(RecordingRuleMatcher.sameProgramme("Law & Order", "Law and Order"))
        assertTrue(RecordingRuleMatcher.sameProgramme("Law & Order", "Law - Order"))
    }

    @Test
    fun `matching is exact after folding, never fuzzy`() {
        // The whole reason: a rule that quietly starts recording Newsnight because the user asked for
        // The News is worse than one that misses a showing — the user finds out when the disk is full.
        assertFalse(RecordingRuleMatcher.sameProgramme("The News", "Newsnight"))
        assertFalse(RecordingRuleMatcher.sameProgramme("The News", "The News Quiz"))
        assertFalse(RecordingRuleMatcher.sameProgramme("The News", "Breakfast News"))
    }

    @Test
    fun `a title that folds away to nothing matches nothing`() {
        assertFalse(RecordingRuleMatcher.sameProgramme("", ""))
        assertFalse(RecordingRuleMatcher.sameProgramme("   ", "   "))
        // …and no rule can be built from one, so nothing is ever scheduled against it.
        assertEquals("", RecordingRuleMatcher.fold("  "))
    }

    @Test
    fun `stripping a trailing tag never leaves a title empty`() {
        // A programme called only "(HD)" is not real, but a fold that returned "" for it would make
        // the guard above fire on a title the provider did supply. It keeps what it has.
        assertTrue(RecordingRuleMatcher.fold("(HD)").isNotEmpty())
    }

    // --- Which showings get a timer ---

    private fun programme(id: Long, title: String, start: Long) = EpgProgrammeEntity(
        id = id,
        sourceId = 1,
        epgChannelId = "bbc1",
        startMs = start,
        stopMs = start + hour,
        title = title,
    )

    private fun recording(
        start: Long,
        channelId: Long = 7,
        status: RecordingStatus = RecordingStatus.SCHEDULED,
        ruleId: Long? = 1,
    ) = RecordingEntity(
        id = start,
        profileId = 1,
        sourceId = 1,
        channelId = channelId,
        channelName = "BBC One",
        streamUrl = "http://example.invalid/1",
        title = "The News",
        programmeStartMs = start,
        programmeStopMs = start + hour,
        startMs = start,
        stopMs = start + hour,
        status = status,
        ruleId = ruleId,
    )

    private val key = RecordingRuleMatcher.fold("The News")

    @Test
    fun `every future showing of the title gets a timer`() {
        val programmes = listOf(
            programme(1, "The News", nine),
            programme(2, "The News (HD)", nine + 24 * hour),
            programme(3, "Newsnight", nine + 25 * hour),
        )
        val picked = RecordingRuleMatcher.showingsToSchedule(key, 7, programmes, emptyList(), nine - hour)
        assertEquals(listOf(1L, 2L), picked.map { it.id })
    }

    @Test
    fun `a showing that has already finished is not scheduled`() {
        // A rule is a standing instruction about the future; the archive is catch-up's job.
        val programmes = listOf(programme(1, "The News", nine - 5 * hour), programme(2, "The News", nine + hour))
        val picked = RecordingRuleMatcher.showingsToSchedule(key, 7, programmes, emptyList(), nine)
        assertEquals(listOf(2L), picked.map { it.id })
    }

    @Test
    fun `a showing already scheduled is not scheduled twice`() {
        // This is what stops every guide refresh re-scheduling the same week.
        val programmes = listOf(programme(1, "The News", nine), programme(2, "The News", nine + 24 * hour))
        val picked = RecordingRuleMatcher.showingsToSchedule(
            key, 7, programmes, listOf(recording(nine)), nine - hour,
        )
        assertEquals(listOf(2L), picked.map { it.id })
    }

    @Test
    fun `a showing the user cancelled by hand stays cancelled`() {
        // Telling the app "not this one" and having it come back on the next refresh would make the
        // rule impossible to live with.
        val programmes = listOf(programme(1, "The News", nine))
        val picked = RecordingRuleMatcher.showingsToSchedule(
            key, 7, programmes, listOf(recording(nine, status = RecordingStatus.CANCELLED)), nine - hour,
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `an already recorded showing is not recorded again`() {
        val programmes = listOf(programme(1, "The News", nine))
        val picked = RecordingRuleMatcher.showingsToSchedule(
            key, 7, programmes, listOf(recording(nine, status = RecordingStatus.COMPLETED)), nine - hour,
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a recording of the same programme on a different channel does not block this one`() {
        // The rule is scoped to one channel, so the same title elsewhere is a different instruction.
        val programmes = listOf(programme(1, "The News", nine))
        val picked = RecordingRuleMatcher.showingsToSchedule(
            key, 7, programmes, listOf(recording(nine, channelId = 99)), nine - hour,
        )
        assertEquals(listOf(1L), picked.map { it.id })
    }

    @Test
    fun `a rule with no usable title schedules nothing`() {
        val programmes = listOf(programme(1, "The News", nine))
        assertTrue(RecordingRuleMatcher.showingsToSchedule("", 7, programmes, emptyList(), nine - hour).isEmpty())
    }

    // --- Turning a rule off ---

    @Test
    fun `turning a rule off cancels only what has not happened yet`() {
        val rows = listOf(
            recording(nine, status = RecordingStatus.SCHEDULED),
            recording(nine + hour, status = RecordingStatus.RECORDING),
            recording(nine - hour, status = RecordingStatus.COMPLETED),
            recording(nine + 2 * hour, status = RecordingStatus.SCHEDULED, ruleId = 2),
        )
        val pending = RecordingRuleMatcher.pendingFor(ruleId = 1, recordings = rows)
        // Only the scheduled row belonging to THIS rule. Not the one being recorded right now, not
        // last week's file, and not another rule's.
        assertEquals(listOf(nine), pending.map { it.programmeStartMs })
    }
}
