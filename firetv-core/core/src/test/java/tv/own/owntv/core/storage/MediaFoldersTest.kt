package tv.own.owntv.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.core.model.MediaType

/**
 * The one thing this phase must not do is move anybody's files: every path here has to come out
 * byte-identical to what the two apps were building by hand before.
 */
class MediaFoldersTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the folder names are the ones already on disk`() {
        assertEquals("TV", MediaFolders.TV)
        assertEquals("Movies", MediaFolders.MOVIES)
        assertEquals("Series", MediaFolders.SERIES)
    }

    /**
     * The bug this pins: a download of an episode is filed as EPISODE, never SERIES, and both apps
     * had independently written `mediaType == SERIES` for their Series tab. An episode downloaded,
     * reported progress, wrote itself to the right folder, and appeared in no list at all.
     */
    @Test
    fun `an episode belongs under Series, which is what both apps got wrong`() {
        assertEquals(MediaFolders.SERIES, MediaFolders.folderFor(MediaType.EPISODE))
        assertEquals(MediaFolders.SERIES, MediaFolders.folderFor(MediaType.SERIES))
        assertEquals(MediaFolders.MOVIES, MediaFolders.folderFor(MediaType.MOVIE))
        assertEquals(MediaFolders.TV, MediaFolders.folderFor(MediaType.LIVE))
    }

    @Test
    fun `every media type has a folder, so a new one cannot be silently unlisted`() {
        MediaType.entries.forEach { type ->
            assertTrue("$type has no folder", MediaFolders.folderFor(type).isNotBlank())
        }
    }

    @Test
    fun `a season path matches what both apps built by hand`() {
        assertEquals("Series/The Wire/Season 3", MediaFolders.seasonDir("The Wire", 3))
    }

    @Test
    fun `a show name that cannot be a directory is sanitised, not rejected`() {
        val dir = MediaFolders.seasonDir("Marvel's What If…?", 1)
        assertTrue(dir.startsWith("Series/"))
        assertTrue(dir.endsWith("/Season 1"))
        // Whatever sanitise does to it, the result must still be one path segment for the show.
        assertEquals(3, dir.split("/").size)
    }

    @Test
    fun `a crumb starts at the library folder, not at the volume`() {
        assertEquals(
            "Series › The Wire › Season 3",
            MediaFolders.crumb(
                "/storage/emulated/0/Android/data/tv.own.owntv/files/OwnTV/Series/The Wire/Season 3/ep1.mkv",
                " › ",
            ),
        )
        // A film sits directly in Movies, so its trail is that one step.
        assertEquals("Movies", MediaFolders.crumb("/sdcard/OwnTV/Movies/Interstellar.mp4", " › "))
    }

    /**
     * The same row, for a file in a folder the user picked through the system picker. It has to read
     * identically — a download does not change what it is because of where it was saved.
     */
    @Test
    fun `a document URI produces the same crumb as the path it describes`() {
        assertEquals(
            "Series › The Wire › Season 3",
            MediaFolders.crumb(
                "content://com.android.externalstorage.documents/tree/primary%3AOwnTV/document/" +
                    "primary%3AOwnTV%2FSeries%2FThe%20Wire%2FSeason%203%2Fep1.mkv",
                " › ",
            ),
        )
        assertEquals(
            "Movies",
            MediaFolders.crumb(
                "content://com.android.externalstorage.documents/tree/primary%3AOwnTV/document/" +
                    "primary%3AOwnTV%2FMovies%2FInterstellar.mp4",
                " › ",
            ),
        )
    }

    @Test
    fun `a document on a card names the folders, never the volume`() {
        // The volume ("1B0A-4C2D") sits before the colon and says nothing about where to look.
        assertEquals(
            "TV › BBC One",
            MediaFolders.crumb(
                "content://com.android.externalstorage.documents/tree/1B0A-4C2D%3AOwnTV/document/" +
                    "1B0A-4C2D%3AOwnTV%2FTV%2FBBC%20One%2Fnews.ts",
                " › ",
            ),
        )
    }

    @Test
    fun `a file under none of the three still says something useful`() {
        assertEquals("b › c › d", MediaFolders.crumb("/a/b/c/d/file.mp4", " › "))
    }

    @Test
    fun `no path yet means no crumb`() {
        assertEquals(null, MediaFolders.crumb(null, " › "))
        assertEquals(null, MediaFolders.crumb("", " › "))
    }

    @Test
    fun `ensureIn creates all three and is safe to call twice`() {
        val root = temp.newFolder("OwnTV")
        MediaFolders.ensureIn(root)
        MediaFolders.ensureIn(root)
        listOf(MediaFolders.TV, MediaFolders.MOVIES, MediaFolders.SERIES).forEach { name ->
            assertTrue("$name should exist", java.io.File(root, name).isDirectory)
        }
    }
}
