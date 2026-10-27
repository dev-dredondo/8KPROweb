package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.stalker.StalkerClient

/**
 * A portal can serve a `get_all_channels` dump that looks complete and still omit a whole genre.
 * Observed on a live portal: its adult genre is flagged `censored`, every one of its 174 channels is
 * absent from the dump, and the `"*"` total the completeness check compares against is filtered the
 * same way — so both numbers agree at 11,494 while 174 channels are missing. The category row is
 * still created from `get_genres`, so the app shows the genre with nothing in it.
 *
 * The dump can only be judged by what it mentions, so that is what this checks.
 */
class StalkerBulkGenreGapTest {

    private fun genre(id: String) = StalkerClient.Genre(id = id, title = "genre $id")

    private fun channel(id: String, genreId: String?) = StalkerClient.Channel(
        id = id,
        name = "channel $id",
        number = null,
        cmd = "ffmpeg http://portal/play/live.php?stream=$id",
        logo = null,
        xmltvId = null,
        genreId = genreId,
        archive = false,
        archiveDuration = 0,
    )

    @Test
    fun `a genre the dump never mentions is reported missing`() {
        val genres = listOf(genre("1430"), genre("1511"), genre("9"))
        val bulk = listOf(channel("1", "1430"), channel("2", "1511"), channel("3", "1430"))

        val missing = StalkerSyncer.genresMissingFromBulk(genres, bulk)

        assertEquals(listOf("9"), missing.map { it.id })
    }

    @Test
    fun `a dump covering every genre asks for nothing extra`() {
        val genres = listOf(genre("1430"), genre("1511"))
        val bulk = listOf(channel("1", "1430"), channel("2", "1511"))

        assertTrue(StalkerSyncer.genresMissingFromBulk(genres, bulk).isEmpty())
    }

    @Test
    fun `channels with no genre id do not vouch for any genre`() {
        // A blank or absent tv_genre_id says nothing about which genres the dump covered, so it must
        // not be read as "some genre is present" — otherwise a portal that omits the field entirely
        // would suppress the backfill for every genre.
        val genres = listOf(genre("1430"), genre("9"))
        val bulk = listOf(channel("1", null), channel("2", ""))

        val missing = StalkerSyncer.genresMissingFromBulk(genres, bulk)

        assertEquals(listOf("1430", "9"), missing.map { it.id })
    }

    @Test
    fun `an empty dump reports every genre`() {
        val genres = listOf(genre("1430"), genre("9"))

        assertEquals(2, StalkerSyncer.genresMissingFromBulk(genres, emptyList()).size)
    }
}
