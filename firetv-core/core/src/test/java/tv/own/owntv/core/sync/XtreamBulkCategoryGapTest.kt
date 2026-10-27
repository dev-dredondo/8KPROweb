package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.parser.XtCategory

/**
 * The Xtream half of the same hole the Stalker path had (see [StalkerBulkGenreGapTest]): a panel can
 * list a category in `get_*_categories` and serve none of its streams in the bulk list, because
 * XUI-style panels gate content — adult content in practice — per line. The category then appears in
 * the app with nothing in it. `&category_id=` still serves those items.
 *
 * The dangerous case is a dump that carries no category ids at all: every category would look absent,
 * the whole catalog would be fetched a second time, and a FRESH source has no duplicate filter.
 */
class XtreamBulkCategoryGapTest {

    private fun cat(id: String) = XtCategory(id = id, name = "category $id")

    @Test
    fun `a category the bulk list never referenced is reported missing`() {
        val cats = listOf(cat("1339"), cat("1923"), cat("77"))

        val missing = XtreamSyncer.categoriesMissingFromBulk(cats, setOf("1339", "1923"))

        assertEquals(listOf("77"), missing.map { it.id })
    }

    @Test
    fun `a bulk list covering every category asks for nothing extra`() {
        val cats = listOf(cat("1339"), cat("1923"))

        assertTrue(XtreamSyncer.categoriesMissingFromBulk(cats, setOf("1339", "1923")).isEmpty())
    }

    @Test
    fun `a bulk list with no category ids at all backfills nothing`() {
        // Not this bug, and acting on it would re-fetch the entire catalog into a source that cannot
        // deduplicate it.
        val cats = listOf(cat("1339"), cat("1923"))

        assertTrue(XtreamSyncer.categoriesMissingFromBulk(cats, emptySet()).isEmpty())
    }

    @Test
    fun `a category id the panel no longer lists does not resurrect a category`() {
        // The dump may reference a category the category list has since dropped; the backfill is
        // driven by the category list, so that id simply has nothing to match.
        val cats = listOf(cat("1339"))

        assertTrue(XtreamSyncer.categoriesMissingFromBulk(cats, setOf("1339", "9999")).isEmpty())
    }
}
