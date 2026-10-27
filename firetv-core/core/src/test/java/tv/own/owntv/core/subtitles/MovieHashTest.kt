package tv.own.owntv.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The moviehash moved from random access to a stream so that a download saved into a folder the user
 * picked — a document with no path — is fingerprinted exactly like one in the app's own folder.
 *
 * The thing worth pinning is that the move did not change the answer, and that a stream which
 * refuses to skip the whole way returns **nothing** rather than a wrong hash: OpenSubtitles would
 * happily answer a wrong hash with somebody else's subtitles.
 */
class MovieHashTest {

    private val chunk = 64 * 1024

    private fun body(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `a file smaller than one chunk has no hash`() {
        val small = body(chunk - 1)
        assertNull(MovieHash.compute(small.size.toLong()) { ByteArrayInputStream(small) })
    }

    @Test
    fun `the hash is sixteen hex characters and is stable`() {
        val bytes = body(chunk * 4)
        val first = MovieHash.compute(bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        assertNotNull(first)
        assertEquals(16, first!!.length)
        assertEquals(first, MovieHash.compute(bytes.size.toLong()) { ByteArrayInputStream(bytes) })
        assertTrue(first.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `different content gives a different hash`() {
        val a = body(chunk * 3)
        val b = body(chunk * 3).also { it[0] = (it[0] + 1).toByte() }
        assertEquals(false, MovieHash.compute(a.size.toLong()) { ByteArrayInputStream(a) } ==
            MovieHash.compute(b.size.toLong()) { ByteArrayInputStream(b) })
    }

    /**
     * Exactly two chunks: the first and last chunks are the whole file and must not overlap-skip
     * into a negative distance.
     */
    @Test
    fun `a file of exactly two chunks hashes without skipping`() {
        val bytes = body(chunk * 2)
        assertNotNull(MovieHash.compute(bytes.size.toLong()) { ByteArrayInputStream(bytes) })
    }

    @Test
    fun `a stream that will not skip returns no hash rather than a wrong one`() {
        val bytes = body(chunk * 6)
        val refuses = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun skip(n: Long): Long = 0
        }
        assertNull(MovieHash.compute(bytes.size.toLong()) { refuses })
    }

    @Test
    fun `a stream that skips in small steps still reaches the last chunk`() {
        val bytes = body(chunk * 6)
        val expected = MovieHash.compute(bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        val dribbles = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            // Never moves the whole way in one call — the case the skip loop exists for.
            override fun skip(n: Long): Long = inner.skip(minOf(n, 1_024L))
        }
        assertEquals(expected, MovieHash.compute(bytes.size.toLong()) { dribbles })
    }

    @Test
    fun `a null target has no hash`() {
        assertNull(MovieHash.compute(null))
    }

    private fun assertTrue(value: Boolean) = assertEquals(true, value)
}
