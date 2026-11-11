package dev.weakbridge.artwork

/**
 * Minimal reader for the small JSON answers the catalogs return.
 *
 * The app deliberately carries no JSON library: only a handful of fields are needed, the payloads
 * are flat, and a hand-written reader keeps the APK small and the parsing predictable. It is not a
 * general parser — it understands objects, arrays of objects and string values, which is all the
 * Deezer and Apple responses use.
 */
internal class JsonCursor(private val text: String) {
    private var index = 0

    /** Top-level array under [key], split into independent cursors per object. */
    fun objects(key: String): List<JsonCursor> {
        val arrayStart = indexOfValue(key) ?: return emptyList()
        val open = text.indexOf('[', arrayStart)
        if (open < 0) return emptyList()
        val close = matching(open, '[', ']')
        if (close < 0) return emptyList()
        val result = mutableListOf<JsonCursor>()
        var depth = 0
        var entryStart = -1
        var inString = false
        var escaped = false
        for (position in open + 1 until close) {
            val char = text[position]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) entryStart = position
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && entryStart >= 0) {
                        result += JsonCursor(text.substring(entryStart, position + 1))
                        entryStart = -1
                    }
                }
            }
        }
        return result
    }

    /** First occurrence of [key] as a well-formed string value, or null. */
    fun string(key: String): String? {
        var searchFrom = 0
        while (true) {
            val valueStart = indexOfValue(key, searchFrom) ?: return null
            val quote = text.indexOf('"', valueStart)
            if (quote < 0) return null
            val end = closingQuote(quote)
            if (end < 0) return null
            return unescape(text.substring(quote + 1, end))
        }
    }

    /** Nested object under [key] as its own cursor. */
    fun child(key: String): JsonCursor? {
        val valueStart = indexOfValue(key) ?: return null
        val open = text.indexOf('{', valueStart)
        if (open < 0) return null
        val close = matching(open, '{', '}')
        if (close < 0) return null
        return JsonCursor(text.substring(open, close + 1))
    }

    private fun indexOfValue(key: String, from: Int = 0): Int? {
        val needle = "\"$key\""
        var at = text.indexOf(needle, from)
        while (at >= 0) {
            var after = at + needle.length
            while (after < text.length && text[after].isWhitespace()) after++
            if (after < text.length && text[after] == ':') return after + 1
            at = text.indexOf(needle, at + needle.length)
        }
        return null
    }

    private fun closingQuote(openQuote: Int): Int {
        var escaped = false
        for (position in openQuote + 1 until text.length) {
            val char = text[position]
            if (escaped) {
                escaped = false
                continue
            }
            when (char) {
                '\\' -> escaped = true
                '"' -> return position
            }
        }
        return -1
    }

    private fun matching(openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (position in openIndex until text.length) {
            val char = text[position]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return position
                }
            }
        }
        return -1
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var position = 0
        while (position < value.length) {
            val char = value[position]
            if (char != '\\' || position + 1 >= value.length) {
                out.append(char)
                position++
                continue
            }
            when (val next = value[position + 1]) {
                'u' -> {
                    if (position + 5 < value.length) {
                        val hex = value.substring(position + 2, position + 6)
                        out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        position += 6
                    } else position++
                }
                'n' -> {
                    out.append('\n')
                    position += 2
                }
                't' -> {
                    out.append('\t')
                    position += 2
                }
                else -> {
                    out.append(next)
                    position += 2
                }
            }
        }
        return out.toString()
    }
}
