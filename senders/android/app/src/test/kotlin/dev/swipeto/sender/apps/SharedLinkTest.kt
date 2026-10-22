package dev.swipeto.sender.apps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SharedLinkTest {
    @Test
    fun `YouTube links of every shape give the video id`() {
        val expected = SharedLink("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "youtube", "aqz-KE-bpKQ")
        listOf(
            "https://youtu.be/aqz-KE-bpKQ?si=Abc123xyz",
            "Big Buck Bunny https://youtu.be/aqz-KE-bpKQ",
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=42s",
            "https://m.youtube.com/watch?feature=share&v=aqz-KE-bpKQ",
            "https://youtube.com/shorts/aqz-KE-bpKQ?feature=share",
            "https://www.youtube.com/live/aqz-KE-bpKQ",
        ).forEach { assertEquals(expected, SharedLink.parse(it), it) }
    }

    @Test
    fun `other pages are web links, https only`() {
        assertEquals(SharedLink("https://vimeo.com/76979871", "web"), SharedLink.parse("Watch this: https://vimeo.com/76979871."))
        assertNull(SharedLink.parse("http://example.com/video"))
        assertNull(SharedLink.parse("no link here"))
        assertNull(SharedLink.parse(null))
        assertEquals("web", SharedLink.parse("https://www.youtube.com/@Blender")?.service) // a channel page, not a video
    }
}
