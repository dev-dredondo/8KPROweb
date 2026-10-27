package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType

/**
 * The rail and a category move must build the same list — the reason this file exists. Both apps
 * used to order categories twice, once for the rail and once for the move, and a move that walks a
 * different list reorders something the user is not looking at.
 */
class CategoryOrderingTest {

    private fun cat(id: Long, name: String) =
        CategoryEntity(id = id, sourceId = 1, mediaType = MediaType.LIVE, name = name, remoteId = "r$id")

    private val cats = listOf(cat(1, "Sports"), cat(2, "News"), cat(3, "Adult"), cat(4, "Movies"))

    private fun keyOf(id: Long) = CustomizeKeys.category(cats.first { it.id == id })

    @Test
    fun `rail keeps playlist order and applies hides and renames`() {
        val c = SectionCustomizations(
            hiddenCategories = setOf(keyOf(2)),
            categoryNames = mapOf(keyOf(1) to "Sport"),
        )
        val rows = cats.railCategories(c, kids = false)
        assertEquals(listOf("Sport", "Adult", "Movies"), rows.map { it.displayName })
    }

    @Test
    fun `a kids profile never sees an adult category`() {
        val rows = cats.railCategories(SectionCustomizations(), kids = true)
        assertEquals(listOf("Sports", "News", "Movies"), rows.map { it.displayName })
    }

    @Test
    fun `custom categories lead, and A-Z sorts only what was never moved`() {
        val c = SectionCustomizations(
            customCategories = listOf(CustomCategory(id = "custom:x", name = "Mine")),
            categoryOrder = listOf(keyOf(4)),
        )
        val rows = cats.railCategories(c, kids = false, alphaRest = true)
        assertEquals(listOf("Movies", "Adult", "Mine", "News", "Sports"), rows.map { it.displayName })
    }

    @Test
    fun `a move walks the rail's own list, and committing it reproduces that order`() {
        val c = SectionCustomizations()
        val rows = cats.railCategories(c, kids = false)
        val move = CategoryMove.begin(rows, keyOf(4))!!

        assertEquals(rows.map { it.key }, move.keys)
        assertEquals(3, move.activeIndex)

        val up = move.moved(MoveKind.UP)!!.moved(MoveKind.UP)!!
        assertEquals(listOf("Sports", "Movies", "News", "Adult"), up.items)
        assertEquals(1, up.activeIndex)

        // What the store is given must be what the rail then shows.
        val committed = c.copy(categoryOrder = up.keys)
        assertEquals(up.items, cats.railCategories(committed, kids = false).map { it.displayName })
    }

    @Test
    fun `a move off either end is a no-op, and a category that is not on the rail cannot move`() {
        val rows = cats.railCategories(SectionCustomizations(), kids = false)
        assertNull(CategoryMove.begin(rows, keyOf(1))!!.moved(MoveKind.UP))
        assertNull(CategoryMove.begin(rows, keyOf(4))!!.moved(MoveKind.DOWN))
        assertNull(CategoryMove.begin(rows, "custom:gone"))
    }

    @Test
    fun `move to top and bottom reach the ends`() {
        val rows = cats.railCategories(SectionCustomizations(), kids = false)
        val top = CategoryMove.begin(rows, keyOf(3))!!.moved(MoveKind.TOP)!!
        assertEquals(listOf("Adult", "Sports", "News", "Movies"), top.items)
        assertEquals(0, top.activeIndex)

        val bottom = CategoryMove.begin(rows, keyOf(1))!!.moved(MoveKind.BOTTOM)!!
        assertEquals(listOf("News", "Adult", "Movies", "Sports"), bottom.items)
        assertEquals(3, bottom.activeIndex)
    }
}
