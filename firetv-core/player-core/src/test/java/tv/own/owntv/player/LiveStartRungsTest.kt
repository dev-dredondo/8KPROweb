package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.player.LiveStartRungs.Rung

class LiveStartRungsTest {

    private val none = LiveStartRungs.Input(
        containerUnsupported = false, formatFailure = false, decoderFailure = false, decoderExhausted = false,
        identityRefusal = false, activeIsHls = false, activeIsDash = false, responseWasHls = false,
        responseWasDash = false, hlsFromHostLesson = false, redirectedHlsRetryDone = false,
        redirectedDashRetryDone = false, sessionLimitSeen = false, sessionLimitRetryDone = false,
        fallbackUserAgentAvailable = true, decoderRetryDone = false, altFormatRetryDone = false,
        directSourceUseful = false,
    )

    @Test
    fun `a ts endpoint that answered HLS reopens as HLS, once`() {
        val i = none.copy(responseWasHls = true, containerUnsupported = true, formatFailure = true)
        assertEquals(Rung.REDIRECT_HLS, LiveStartRungs.before(i))
        assertNull(LiveStartRungs.before(i.copy(redirectedHlsRetryDone = true)))
        assertNull(LiveStartRungs.before(i.copy(activeIsHls = true)))
    }

    @Test
    fun `a wrong HLS lesson, DASH and the session wait`() {
        assertEquals(Rung.DROP_HOST_HLS_LESSON, LiveStartRungs.before(none.copy(hlsFromHostLesson = true, formatFailure = true)))
        assertEquals(Rung.REDIRECT_DASH, LiveStartRungs.before(none.copy(responseWasDash = true, containerUnsupported = true)))
        assertNull(LiveStartRungs.before(none.copy(responseWasDash = true, containerUnsupported = true, activeIsDash = true)))
        assertEquals(Rung.SESSION_WAIT, LiveStartRungs.before(none.copy(sessionLimitSeen = true)))
        assertNull(LiveStartRungs.before(none.copy(sessionLimitSeen = true, sessionLimitRetryDone = true)))
    }

    @Test
    fun `after the back-off, in order`() {
        assertEquals(Rung.FALLBACK_USER_AGENT, LiveStartRungs.after(none.copy(identityRefusal = true, decoderFailure = true)))
        assertEquals(Rung.DECODER_EXHAUSTED, LiveStartRungs.after(none.copy(decoderExhausted = true, decoderFailure = true)))
        assertEquals(Rung.REBUILD_DECODER, LiveStartRungs.after(none.copy(decoderFailure = true, formatFailure = true)))
        assertEquals(Rung.ALT_FORMAT, LiveStartRungs.after(none.copy(decoderFailure = true, decoderRetryDone = true, formatFailure = true)))
        assertEquals(Rung.DIRECT_SOURCE, LiveStartRungs.after(none.copy(formatFailure = true, altFormatRetryDone = true, directSourceUseful = true)))
        assertEquals(Rung.FAIL, LiveStartRungs.after(none))
    }

    @Test
    fun `no user-agent retry once it is spent or the source sets its own`() {
        assertEquals(Rung.FAIL, LiveStartRungs.after(none.copy(identityRefusal = true, fallbackUserAgentAvailable = false)))
    }
}
