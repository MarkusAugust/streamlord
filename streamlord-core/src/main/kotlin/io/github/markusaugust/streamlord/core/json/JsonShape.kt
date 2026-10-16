package io.github.markusaugust.streamlord.core.json

/*
 * Whether text is JSON, without building a value: a single pass, and a stack of open containers
 * rather than recursion, so no depth is too deep for it. Signals Streamlord sends are held to it,
 * since Datastar's client runs as JavaScript any signals text that JSON.parse refuses.
 */
internal object JsonShape {
    private const val KEY_OR_CLOSE = 0
    private const val KEY = 1
    private const val COLON = 2
    private const val VALUE_OR_CLOSE = 3
    private const val VALUE = 4
    private const val AFTER = 5

    /** Whether [text] is one JSON object and nothing else, as RFC 8259 writes it. */
    fun isObject(text: String): Boolean {
        val n = text.length
        var i = space(text, 0)
        if (i >= n || text[i] != '{') return false
        val open = ArrayDeque<Char>()
        open.addLast('{')
        i++
        var state = KEY_OR_CLOSE
        while (true) {
            if (state == AFTER && open.isEmpty()) return space(text, i) == n
            i = space(text, i)
            if (i >= n) return false
            val c = text[i]
            when (state) {
                KEY_OR_CLOSE, KEY -> {
                    if (c == '}' && state == KEY_OR_CLOSE) {
                        open.removeLast()
                        i++
                        state = AFTER
                    } else {
                        i = string(text, i)
                        if (i < 0) return false
                        state = COLON
                    }
                }

                COLON -> {
                    if (c != ':') return false
                    i++
                    state = VALUE
                }

                VALUE_OR_CLOSE, VALUE -> {
                    when {
                        c == ']' && state == VALUE_OR_CLOSE -> {
                            open.removeLast()
                            i++
                            state = AFTER
                        }

                        c == '{' -> {
                            open.addLast('{')
                            i++
                            state = KEY_OR_CLOSE
                        }

                        c == '[' -> {
                            open.addLast('[')
                            i++
                            state = VALUE_OR_CLOSE
                        }

                        else -> {
                            i = scalar(text, i)
                            if (i < 0) return false
                            state = AFTER
                        }
                    }
                }

                else -> {
                    val top = open.last()
                    when {
                        c == ',' -> {
                            i++
                            state = if (top == '{') KEY else VALUE
                        }

                        (c == '}' && top == '{') || (c == ']' && top == '[') -> {
                            open.removeLast()
                            i++
                        }

                        else -> {
                            return false
                        }
                    }
                }
            }
        }
    }

    /** Whether [text] is a JSON number and nothing else. */
    fun isNumber(text: String): Boolean = number(text, 0) == text.length

    private fun space(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i++
        return i
    }

    /** One past the string, number or literal at [from], or -1 when there is none. */
    private fun scalar(
        text: String,
        from: Int,
    ): Int =
        when (text[from]) {
            '"' -> string(text, from)
            't' -> literal(text, from, "true")
            'f' -> literal(text, from, "false")
            'n' -> literal(text, from, "null")
            else -> number(text, from)
        }

    private fun literal(
        text: String,
        from: Int,
        word: String,
    ): Int = if (text.startsWith(word, from)) from + word.length else -1

    private fun string(
        text: String,
        from: Int,
    ): Int {
        if (text.getOrNull(from) != '"') return -1
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    return i + 1
                }

                c == '\\' -> {
                    val escape = text.getOrNull(i + 1) ?: return -1
                    i +=
                        when (escape) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> 2
                            'u' -> if ((2..5).all { text.getOrNull(i + it)?.isHex() == true }) 6 else return -1
                            else -> return -1
                        }
                }

                c < ' ' -> {
                    return -1
                }

                else -> {
                    i++
                }
            }
        }
        return -1
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** One past the number at [from], or -1: `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. */
    private fun number(
        text: String,
        from: Int,
    ): Int {
        var i = from
        if (text.getOrNull(i) == '-') i++
        when (text.getOrNull(i)) {
            '0' -> i++
            in '1'..'9' -> i = digits(text, i)
            else -> return -1
        }
        if (text.getOrNull(i) == '.') {
            val after = digits(text, i + 1)
            if (after == i + 1) return -1
            i = after
        }
        if (text.getOrNull(i) == 'e' || text.getOrNull(i) == 'E') {
            var j = i + 1
            if (text.getOrNull(j) == '+' || text.getOrNull(j) == '-') j++
            val after = digits(text, j)
            if (after == j) return -1
            i = after
        }
        return i
    }

    private fun digits(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }
}
