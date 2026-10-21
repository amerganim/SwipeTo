package dev.swipeto.sender.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RangeRequestTest {
    private val size = 1000L

    @Test
    fun `no header or junk means the whole file`() {
        assertEquals(RangeRequest.Full, RangeRequest.parse(null, size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("items=0-5", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=0-5,10-20", size)) // several ranges: allowed to ignore
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=-", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=9-3", size))
    }

    @Test
    fun `closed, open-ended and suffix ranges`() {
        assertEquals(RangeRequest.Partial(0, 99), RangeRequest.parse("bytes=0-99", size))
        assertEquals(RangeRequest.Partial(500, 999), RangeRequest.parse("bytes=500-", size))
        assertEquals(RangeRequest.Partial(900, 999), RangeRequest.parse("bytes=-100", size))
        assertEquals(RangeRequest.Partial(0, 999), RangeRequest.parse("bytes=-5000", size))
        assertEquals(RangeRequest.Partial(990, 999), RangeRequest.parse("bytes=990-5000", size)) // end clamped
        assertEquals(100, (RangeRequest.parse("bytes=0-99", size) as RangeRequest.Partial).length)
    }

    @Test
    fun `starting past the end is unsatisfiable`() {
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=1000-", size))
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=-0", size))
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=0-1", 0))
    }
}
