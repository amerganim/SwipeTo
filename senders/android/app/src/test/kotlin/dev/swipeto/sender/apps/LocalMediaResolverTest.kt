package dev.swipeto.sender.apps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalMediaResolverTest {
    private fun video(id: Long, name: String, bucket: String, duration: Long?) =
        LibraryVideo(id, name, name.substringBeforeLast('.'), bucket, duration, "video/mp4", 1_000)

    @Test
    fun `A video player's title, album and duration pick the one file`() {
        // What the A15 reported: title=swipeto-sample, ALBUM=SwipeTo, DURATION=180033.
        val sample = video(7, "swipeto-sample.mp4", "SwipeTo", 180_000)
        val match = LocalMediaResolver.chooseMatch(listOf(sample), "swipeto-sample", "SwipeTo", 180_033)

        assertEquals(7L, match?.id)
    }

    @Test
    fun `the folder and the duration tell same-named videos apart`() {
        val camera = video(1, "VID_0001.mp4", "Camera", 12_000)
        val whatsapp = video(2, "VID_0001.mp4", "WhatsApp Video", 45_000)

        assertEquals(2L, LocalMediaResolver.chooseMatch(listOf(camera, whatsapp), "VID_0001", "WhatsApp Video", null)?.id)
        assertEquals(1L, LocalMediaResolver.chooseMatch(listOf(camera, whatsapp), "VID_0001", null, 12_200)?.id)
    }

    @Test
    fun `no match or an ambiguous one is null, never a guess`() {
        val a = video(1, "clip.mp4", "Camera", 30_000)
        val b = video(2, "clip.mp4", "Camera", 30_000)

        assertNull(LocalMediaResolver.chooseMatch(listOf(a, b), "clip", "Camera", 30_000))
        assertNull(LocalMediaResolver.chooseMatch(listOf(a), "clip", "Camera", 95_000))
        assertNull(LocalMediaResolver.chooseMatch(emptyList(), "clip", null, null))
    }
}
