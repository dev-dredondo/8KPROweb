package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOrderMergeTest {

    @Test
    fun `a move on one playlist keeps the other playlist's order`() {
        // Playlist 1 was ordered, then the rail shows playlist 2 and its order is saved.
        val afterOne = mergeCategoryOrder(emptyList(), listOf("1:b", "1:a", "1:c"))
        val afterTwo = mergeCategoryOrder(afterOne, listOf("2:y", "2:x"))
        // Back on playlist 1, its categories still come out as b, a, c.
        val playlistOne = afterTwo.filter { it.startsWith("1:") }
        assertEquals(listOf("1:b", "1:a", "1:c"), playlistOne)
        assertEquals(listOf("2:y", "2:x"), afterTwo.filter { it.startsWith("2:") })
    }

    @Test
    fun `the keys on screen take their new order and lead`() {
        assertEquals(
            listOf("1:c", "1:a", "1:b", "2:x"),
            mergeCategoryOrder(listOf("1:a", "2:x", "1:b", "1:c"), listOf("1:c", "1:a", "1:b")),
        )
    }

    @Test
    fun `a custom category shared by every playlist does not jump ahead of the other playlist`() {
        // Playlist 1's whole rail was ordered with the custom category last.
        val stored = listOf("1:a", "1:b", "custom1")
        // Playlist 2 shows the custom category first, then its own folders.
        val merged = mergeCategoryOrder(stored, listOf("custom1", "2:y", "2:x"))
        // Playlist 1 still sees its folders before the custom category, as before the move.
        assertEquals(listOf("1:a", "1:b", "custom1"), merged.filter { it.startsWith("1:") || it == "custom1" })
        assertEquals(listOf("custom1", "2:y", "2:x"), merged.filter { it.startsWith("2:") || it == "custom1" })
    }

    @Test
    fun `nothing is duplicated`() {
        val merged = mergeCategoryOrder(listOf("1:a", "custom1", "2:x"), listOf("custom1", "1:a"))
        assertEquals(listOf("custom1", "1:a", "2:x"), merged)
    }
}
