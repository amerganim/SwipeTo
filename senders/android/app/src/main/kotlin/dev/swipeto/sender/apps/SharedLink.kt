package dev.swipeto.sender.apps

import java.net.URI

/**
 * A link shared to SwipeTo (Share → SwipeTo in YouTube, a browser...). It names exactly what is
 * playing, which media sessions never do, so a screen can open it itself: YouTube links with
 * YouTube's own player (by id), any other page in the screen's browser.
 */
data class SharedLink(val url: String, val service: String, val contentId: String? = null) {
    companion object {
        private val URL = Regex("""https?://[^\s"'<>]+""")
        private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{6,64}$")
        private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

        /** The first link in [text] (apps share "Title https://..." or just the link); null if none. */
        fun parse(text: String?): SharedLink? {
            val raw = text?.let { URL.find(it)?.value }?.trimEnd('.', ',', ')', ']') ?: return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            youTubeId(host, uri)?.let { id -> return SharedLink("https://www.youtube.com/watch?v=$id", "youtube", id) }
            // Screens open plain pages only over https (docs/protocol.md, contentUrl).
            if (uri.scheme != "https") return null
            return SharedLink(raw, "web")
        }

        private fun youTubeId(host: String, uri: URI): String? {
            val path = uri.path.orEmpty().trim('/')
            val id = when {
                host == "youtu.be" -> path.substringBefore('/')
                host in YOUTUBE_HOSTS && path == "watch" -> query(uri, "v")
                host in YOUTUBE_HOSTS && path.substringBefore('/') in setOf("shorts", "live", "embed") -> path.substringAfter('/').substringBefore('/')
                else -> null
            }
            return id?.takeIf { YOUTUBE_ID.matches(it) }
        }

        private fun query(uri: URI, name: String): String? =
            uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part -> part.split('=', limit = 2).takeIf { it[0] == name }?.getOrNull(1) }
    }
}
