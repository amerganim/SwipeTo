package dev.swipeto.sender.apps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecentMediaTest {
    private val gallery = (0 until 100).toList() // newest first, as Gallery shows them

    @Test
    fun `a shared photo comes with its neighbours, and its place among them`() {
        assertEquals((40..60).toList() to 10, RecentMedia.window(gallery, 50, radius = 10))
    }

    @Test
    fun `at either end of the library the window is cut, not shifted`() {
        assertEquals((0..3).toList() to 0, RecentMedia.window(gallery, 0, radius = 3))
        assertEquals((96..99).toList() to 3, RecentMedia.window(gallery, 99, radius = 3))
        assertEquals(listOf(7) to 0, RecentMedia.window(listOf(7), 0, radius = 25))
    }
}
