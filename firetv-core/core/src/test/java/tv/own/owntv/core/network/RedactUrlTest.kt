package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** Credentials never reach a log line, whatever shape the URL has. */
class RedactUrlTest {

    private fun redact(url: String) = HttpClient.redactUrl(url)

    @Test
    fun `xtream path credentials are masked`() {
        assertEquals("http://host:80/live/•••/•••/123.ts", redact("http://host:80/live/alice/s3cret/123.ts"))
        assertEquals(
            "http://host/timeshift/•••/•••/60/2026-10-20:10-00/9.ts",
            redact("http://host/timeshift/alice/s3cret/60/2026-10-20:10-00/9.ts"),
        )
    }

    @Test
    fun `query credentials are masked`() {
        assertEquals(
            "http://host/play/live.php?mac=00:1A&stream=5&play_token=***",
            redact("http://host/play/live.php?mac=00:1A&stream=5&play_token=abc"),
        )
        assertEquals("http://h/get.php?username=***&password=***&type=m3u", redact("http://h/get.php?username=a&password=b&type=m3u"))
    }

    @Test
    fun `userinfo is masked`() {
        assertEquals("http://***@proxy:8080/x", redact("http://bob:pw@proxy:8080/x"))
    }

    @Test
    fun `a clean url is unchanged`() {
        assertEquals("https://example.org/a/b.m3u8", redact("https://example.org/a/b.m3u8"))
    }
}
