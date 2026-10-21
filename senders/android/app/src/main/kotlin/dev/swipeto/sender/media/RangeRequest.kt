package dev.swipeto.sender.media

/** What a `Range` header asks for, resolved against a file of known size (RFC 9110, section 14). */
sealed interface RangeRequest {
    /** No usable range: send the whole file with 200. */
    data object Full : RangeRequest

    /** Send bytes [start]..[end] inclusive with 206. */
    data class Partial(val start: Long, val end: Long) : RangeRequest {
        val length get() = end - start + 1
    }

    /** The range starts past the end of the file: 416. */
    data object Unsatisfiable : RangeRequest

    companion object {
        private val SINGLE = Regex("""^bytes=(\d*)-(\d*)$""")

        /**
         * Parses `bytes=a-b`, `bytes=a-` and `bytes=-n`. Anything else (other units, several
         * ranges, garbage) is ignored as the RFC allows, and the whole file is sent.
         */
        fun parse(header: String?, size: Long): RangeRequest {
            val match = header?.trim()?.let(SINGLE::matchEntire) ?: return Full
            val (first, last) = match.destructured
            if (first.isEmpty() && last.isEmpty()) return Full
            if (size == 0L) return Unsatisfiable

            if (first.isEmpty()) {
                // Suffix range: the last n bytes.
                val n = last.toLongOrNull() ?: return Full
                if (n == 0L) return Unsatisfiable
                return Partial(maxOf(0, size - n), size - 1)
            }
            val start = first.toLongOrNull() ?: return Full
            if (start >= size) return Unsatisfiable
            val end = if (last.isEmpty()) size - 1 else (last.toLongOrNull() ?: return Full).coerceAtMost(size - 1)
            return if (end < start) Full else Partial(start, end)
        }
    }
}
