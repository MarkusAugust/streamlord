package io.github.markusaugust.streamlord.core.json

import io.github.markusaugust.streamlord.core.JsonParseException

/**
 * A strict RFC 8259 parser with no dependencies and no mercy.
 *
 * It exists so that reading signals never requires a JSON library. It rejects everything the
 * standard rejects (trailing commas, comments, single quotes, leading zeros, unescaped control
 * characters, `NaN`) and additionally caps nesting depth so hostile input cannot blow the stack.
 */
public object JsonParser {
    /** Nesting deeper than this is treated as an attack, not as data. */
    public const val DEFAULT_MAX_DEPTH: Int = 64

    /** Parse any JSON value. */
    public fun parse(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonValue = Cursor(text, maxDepth).parseDocument()

    /** Parse text that must be a JSON object, as signals always are. */
    public fun parseObject(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonObject =
        parse(text, maxDepth) as? JsonObject ?: throw JsonParseException("Expected a JSON object", 0)

    private class Cursor(private val s: String, private val maxDepth: Int) {
        private var i = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (i != s.length) fail("Unexpected trailing content")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (i >= s.length) fail("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonString(parseString())
                't' -> literal("true", JsonBoolean.TRUE)
                'f' -> literal("false", JsonBoolean.FALSE)
                'n' -> literal("null", JsonNull)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected character '$c'")
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            if (depth > maxDepth) fail("Nesting deeper than $maxDepth levels")
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected a string key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = parseValue(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonObject(fields)
                    }

                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            if (depth > maxDepth) fail("Nesting deeper than $maxDepth levels")
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items += parseValue(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonArray(items)
                    }

                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(parseEscape())
                    c < ' ' -> fail("Unescaped control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun parseEscape(): Char {
            if (i >= s.length) fail("Unterminated escape sequence")
            return when (val c = s[i++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> ''
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> fail("Invalid escape '\\$c'")
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (i + 4 > s.length) fail("Truncated unicode escape")
            var code = 0
            repeat(4) {
                val d = Character.digit(s[i++], 16)
                if (d < 0) fail("Invalid hex digit in unicode escape")
                code = (code shl 4) or d
            }
            return code.toChar()
        }

        private fun parseNumber(): JsonNumber {
            val start = i
            if (peek() == '-') i++
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> while (peek()?.isAsciiDigit() == true) i++
                else -> fail("Invalid number")
            }
            if (peek() == '.') {
                i++
                if (peek()?.isAsciiDigit() != true) fail("Invalid number: digit expected after '.'")
                while (peek()?.isAsciiDigit() == true) i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek()?.isAsciiDigit() != true) fail("Invalid number: digit expected in exponent")
                while (peek()?.isAsciiDigit() == true) i++
            }
            return JsonNumber(s.substring(start, i))
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail("Invalid literal")
            i += word.length
            return value
        }

        private fun skipWhitespace() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun expect(c: Char) {
            if (peek() != c) fail("Expected '$c'")
            i++
        }

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

        private fun fail(message: String): Nothing = throw JsonParseException(message, i)
    }
}
