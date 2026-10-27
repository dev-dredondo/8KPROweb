package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * The case that produced this: a channel carried by two EPG feeds. The unique index cannot stop it —
 * the rows differ in `sourceId` and, because two feeds rarely agree to the minute, in `startMs` too —
 * so the guide drew one block on top of another and Next/Later named the same film twice.
 */
class EpgDedupeTest {

    private fun row(
        id: Long,
        start: Long,
        stop: Long,
        title: String,
        sourceId: Long = 1,
    ) = EpgProgrammeEntity(
        id = id,
        sourceId = sourceId,
        epgChannelId = "sky.cinema",
        startMs = start,
        stopMs = stop,
        title = title,
    )

    private val h = 60 * 60_000L

    @Test
    fun `the real case - two feeds, same film, edges a few minutes apart`() {
        val a = row(1, 22 * h + 4 * 60_000, 24 * h + 16 * 60_000, "Snow White and the Huntsman", sourceId = 1)
        val b = row(2, 22 * h + 10 * 60_000, 24 * h + 15 * 60_000, "Snow White and the Huntsman", sourceId = 2)
        val out = EpgDedupe.collapse(listOf(a, b))
        assertEquals(1, out.size)
        // The wider span wins: a block drawn from it leaves no gap either side.
        assertEquals(1L, out.single().id)
    }

    @Test
    fun `an exact duplicate collapses to one`() {
        val a = row(1, 0, 2 * h, "Brothers Grimm", sourceId = 1)
        val b = row(2, 0, 2 * h, "Brothers Grimm", sourceId = 2)
        assertEquals(1, EpgDedupe.collapse(listOf(a, b)).size)
    }

    @Test
    fun `titles differing only in case, padding or wrapping are one programme`() {
        val a = row(1, 0, 2 * h, "The Huntsman & The Ice Queen")
        val b = row(2, 60_000, 2 * h, "  the huntsman &   the ice queen ")
        assertEquals(1, EpgDedupe.collapse(listOf(a, b)).size)
    }

    @Test
    fun `back-to-back programmes are never merged, however they sort`() {
        val first = row(1, 0, 2 * h, "The News")
        val second = row(2, 2 * h, 4 * h, "The Weather")
        assertEquals(2, EpgDedupe.collapse(listOf(second, first)).size)
        // …and they come back in start order.
        assertEquals(listOf(1L, 2L), EpgDedupe.collapse(listOf(second, first)).map { it.id })
    }

    /** A channel really does repeat a programme later in the day. That is two broadcasts, not a bug. */
    @Test
    fun `the same title later in the day is kept`() {
        val morning = row(1, 0, h, "Splitsvilla X")
        val evening = row(2, 8 * h, 9 * h, "Splitsvilla X")
        assertEquals(2, EpgDedupe.collapse(listOf(morning, evening)).size)
    }

    /**
     * Two feeds disagreeing about *what* is on cannot be resolved by throwing one away, so both are
     * kept. Overlap alone is not evidence of a duplicate.
     */
    @Test
    fun `overlapping programmes with different titles are both kept`() {
        val a = row(1, 0, 2 * h, "Der Diktator")
        val b = row(2, h, 3 * h, "Road Trip")
        assertEquals(2, EpgDedupe.collapse(listOf(a, b)).size)
    }

    @Test
    fun `three copies of one programme collapse to the longest`() {
        val short = row(1, 0, h, "Jurassic World")
        val long = row(2, 0, 3 * h, "Jurassic World")
        val middle = row(3, 60_000, 2 * h, "Jurassic World")
        val out = EpgDedupe.collapse(listOf(short, long, middle))
        assertEquals(1, out.size)
        assertEquals(2L, out.single().id)
    }

    @Test
    fun `nothing to do is cheap and safe`() {
        assertEquals(emptyList<EpgProgrammeEntity>(), EpgDedupe.collapse(emptyList()))
        val one = listOf(row(1, 0, h, "Solo"))
        assertEquals(one, EpgDedupe.collapse(one))
    }
}
