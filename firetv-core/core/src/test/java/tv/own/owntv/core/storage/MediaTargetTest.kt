package tv.own.owntv.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rule that let downloads and recordings gain a second kind of destination **without a database
 * migration**: `filePath` stays one `String`, and a `content://` value in it is a document while
 * anything else is a path.
 *
 * Only the path half is exercised here. A document needs a `ContentResolver` and a granted tree, so
 * it belongs to a device test rather than a JVM one — what is pinned here is the decision that sends
 * a stored value down one road or the other, because getting *that* wrong would write a phone's
 * downloads somewhere nobody asked for.
 */
class MediaTargetTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a content URI is a document and everything else is a path`() {
        assertTrue(MediaTarget.isDocument("content://com.android.externalstorage.documents/tree/primary%3AFilms"))
        // Case is the provider's business, not ours.
        assertTrue(MediaTarget.isDocument("CONTENT://whatever"))
        assertFalse(MediaTarget.isDocument("/storage/emulated/0/OwnTV/Movies/x.mp4"))
        assertFalse(MediaTarget.isDocument("file:///sdcard/x.mp4"))
        assertFalse(MediaTarget.isDocument(null))
        assertFalse(MediaTarget.isDocument(""))
    }

    @Test
    fun `a path round-trips through stored unchanged`() {
        val file = File(temp.newFolder("OwnTV"), "Interstellar.mp4")
        assertEquals(file.absolutePath, MediaTarget.Path(file).stored)
        assertEquals("Interstellar.mp4", MediaTarget.Path(file).displayName)
    }

    @Test
    fun `an absent file measures zero rather than failing`() {
        val target = MediaTarget.Path(File(temp.newFolder(), "never-written.mp4"))
        assertFalse(target.exists())
        assertEquals(0L, target.length())
    }

    @Test
    fun `a file can always be appended to, so a path always resumes`() {
        val file = File(temp.newFolder(), "part.mp4").apply { writeBytes(ByteArray(1_200)) }
        assertTrue(MediaTarget.Path(file).canAppend())
        assertEquals(1_200L, MediaTarget.Path(file).length())
    }

    @Test
    fun `truncate on a path removes it, which is what starting over means`() {
        val file = File(temp.newFolder(), "half.mp4").apply { writeBytes(ByteArray(900)) }
        MediaTarget.Path(file).truncate()
        assertFalse(file.exists())
        assertEquals(0L, MediaTarget.Path(file).length())
    }

    @Test
    fun `a destination whose folder is gone is not writable`() {
        val volume = temp.newFolder("removable")
        val target = MediaTarget.Path(File(File(volume, "OwnTV"), "film.mp4"))
        // The parent can be made, so it is writable…
        assertTrue(target.ensureWritable())
        // …but a volume that has been unmounted under it is not, and must not be papered over.
        assertTrue(volume.deleteRecursively())
        assertTrue(volume.createNewFile()) // a file where the directory was: mkdirs cannot succeed
        assertFalse(target.ensureWritable())
    }

    @Test
    fun `writing appends or truncates exactly as asked`() {
        val file = File(temp.newFolder(), "movie.mp4")
        val target = MediaTarget.Path(file)
        target.openOutput(append = false).use { it.write(ByteArray(100)) }
        assertEquals(100L, target.length())
        target.openOutput(append = true).use { it.write(ByteArray(50)) }
        assertEquals(150L, target.length())
        target.openOutput(append = false).use { it.write(ByteArray(10)) }
        assertEquals(10L, target.length())
    }
}
