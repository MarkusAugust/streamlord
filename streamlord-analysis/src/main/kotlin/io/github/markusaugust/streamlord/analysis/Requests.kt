package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.json.JsonValue
import java.net.URLEncoder

/*
 * Saved requests for the Stream Inspector: the file format, variable substitution, the recent
 * list and the curl export. Editor-independent, and the same file format as the VS Code
 * extension, so a team shares `.streamlord/inspector.json` across editors.
 */

public data class SavedRequest(
    val name: String,
    val url: String,
    val method: String = "GET",
    /** Signals as JSON text (may contain variables). */
    val signals: String = "",
    /** Headers as `Name: value` lines (may contain variables). */
    val headers: String = "",
)

public data class RequestsFile(
    val requests: List<SavedRequest>,
) {
    public companion object {
        public val EMPTY: RequestsFile = RequestsFile(emptyList())
    }
}

public object Requests {
    public const val REQUESTS_FILE: String = ".streamlord/inspector.json"
    public const val ENV_FILE: String = ".streamlord/env.json"
    public const val RECENT_LIMIT: Int = 10
    public val METHODS: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "QUERY")

    private val VARIABLE = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*\}\}""")
    private val PATH_PARAM = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(\?)?\}""")
    private val OPTIONAL_SEGMENT = Regex("""/\{([A-Za-z_][A-Za-z0-9_]*)\?\}""")
    private val ANY_PARAM = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\??\}""")
    private val ABSOLUTE = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://""")
    private val PATH_PARAM_TEXT = Regex("""\{[^{}]*\}""")
    private const val NOT_SENDABLE = "\"<>\\^`|"

    /** Parse the requests file leniently: bad entries are dropped, never fatal. */
    public fun parseRequestsFile(text: String): RequestsFile {
        val raw =
            try {
                JsonParser.parse(text)
            } catch (_: Exception) {
                return RequestsFile.EMPTY
            }
        val list = (raw as? JsonObject)?.array("requests") ?: return RequestsFile.EMPTY
        val requests = ArrayList<SavedRequest>()
        for (item in list.objects()) {
            val name = item.string("name") ?: continue
            val url = item.string("url") ?: continue
            val method = (item.string("method") ?: "GET").uppercase()
            val signals = item["signals"]
            val headers = item["headers"]
            requests +=
                SavedRequest(
                    name = name,
                    url = url,
                    method = if (method in METHODS) method else "GET",
                    signals =
                        when (signals) {
                            is JsonString -> signals.value
                            is JsonObject, is JsonArray -> prettyJson(signals)
                            else -> ""
                        },
                    headers =
                        when (headers) {
                            is JsonString -> {
                                headers.value
                            }

                            is JsonObject -> {
                                headers.entries.joinToString(
                                    "\n",
                                ) { (k, v) -> "$k: ${(v as? JsonString)?.value ?: v.toJson()}" }
                            }

                            else -> {
                                ""
                            }
                        },
                )
        }
        return RequestsFile(requests)
    }

    public fun serializeRequestsFile(file: RequestsFile): String {
        val sb = StringBuilder("{\n  \"version\": 1,\n  \"requests\": [")
        file.requests.forEachIndexed { i, r ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("    {\n")
            sb.append("      \"name\": ").append(JsonString(r.name).toJson()).append(",\n")
            sb.append("      \"url\": ").append(JsonString(r.url).toJson()).append(",\n")
            sb.append("      \"method\": ").append(JsonString(r.method).toJson()).append(",\n")
            sb.append("      \"signals\": ").append(JsonString(r.signals).toJson()).append(",\n")
            sb.append("      \"headers\": ").append(JsonString(r.headers).toJson()).append("\n")
            sb.append("    }")
        }
        if (file.requests.isNotEmpty()) sb.append("\n  ")
        sb.append("]\n}\n")
        return sb.toString()
    }

    /**
     * Insert or replace by name (case-insensitive), keeping the list sorted by name.
     *
     * Names are matched and ordered by [nameKey]. The VS Code extension applies the same rule, so
     * the shared file keeps one order whichever editor saves it.
     */
    public fun upsert(
        file: RequestsFile,
        request: SavedRequest,
    ): RequestsFile {
        val key = nameKey(request.name)
        val others = file.requests.filter { nameKey(it.name) != key }
        return RequestsFile((others + request).sortedBy { nameKey(it.name) })
    }

    public fun remove(
        file: RequestsFile,
        name: String,
    ): RequestsFile {
        val key = nameKey(name)
        return RequestsFile(file.requests.filter { nameKey(it.name) != key })
    }

    /**
     * A name in lowercase, one character at a time, compared code unit by code unit. Lowercasing
     * the whole string would depend on the runtime: the JVM and V8 disagree on where a sigma is final.
     */
    private fun nameKey(name: String): String {
        val out = StringBuilder(name.length)
        name.codePoints().forEach { out.append(Character.toString(it).lowercase()) }
        return out.toString()
    }

    /** Parse `{"baseUrl": "..."}`; anything that is not a flat object of strings or numbers is ignored. */
    public fun parseEnvFile(text: String): Map<String, String> {
        val raw =
            try {
                JsonParser.parse(text) as? JsonObject ?: return emptyMap()
            } catch (_: Exception) {
                return emptyMap()
            }
        val out = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            when (v) {
                is JsonString -> {
                    out[k] = v.value
                }

                is JsonNumber -> {
                    out[k] = v.text
                }

                else -> {}
            }
        }
        return out
    }

    public enum class VariableSource(
        internal val label: String,
        internal val phrase: String,
    ) {
        DEFAULT("default", "the default"),
        SETTINGS("settings", "from the settings"),
        ENV(ENV_FILE, "from $ENV_FILE"),
    }

    /** A `{{name}}` value and where it was set, so the inspector can say both. */
    public data class Variable(
        val name: String,
        val value: String,
        val source: VariableSource,
    )

    /**
     * `baseUrl` from the default, then the settings, then `.streamlord/env.json`; a later source
     * replaces the value of an earlier one and keeps its place in the list.
     */
    public fun mergeVariables(
        defaultUrl: String,
        settings: Map<String, String>,
        env: Map<String, String>,
    ): List<Variable> {
        val out = LinkedHashMap<String, Variable>()
        out["baseUrl"] = Variable("baseUrl", defaultUrl.trimEnd('/'), VariableSource.DEFAULT)
        for ((name, value) in settings) out[name] = Variable(name, value, VariableSource.SETTINGS)
        for ((name, value) in env) out[name] = Variable(name, value, VariableSource.ENV)
        return out.values.toList()
    }

    /** The values by name, for substitution. */
    public fun variableValues(vars: List<Variable>): Map<String, String> = vars.associate { it.name to it.value }

    /** One line per variable, with where it was set. */
    public fun describeVariables(vars: List<Variable>): String =
        vars.joinToString("\n") { "${it.name} = ${it.value.ifEmpty { "\"\"" }}   (${it.source.label})" }

    /**
     * The text of `.streamlord/env.json` with [names] added, empty, after what is there. A missing
     * file is written with `baseUrl` first, so it is there to change. The text is extended rather
     * than written anew, so the user's own layout and values stay as they were; null when it is
     * not a JSON object and cannot be extended safely.
     */
    public fun withEnvVariables(
        text: String?,
        names: List<String>,
        baseUrl: String,
    ): String? {
        if (text == null) {
            val entries = listOf("baseUrl" to baseUrl) + names.filter { it != "baseUrl" }.map { it to "" }
            return entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  ${JsonString(k).toJson()}: ${JsonString(v).toJson()}" }
        }
        val raw =
            try {
                JsonParser.parse(text) as? JsonObject ?: return null
            } catch (_: Exception) {
                return null
            }
        val missing = names.distinct().filter { it !in raw }
        if (missing.isEmpty()) return text
        val close = text.lastIndexOf('}')
        val before = text.substring(0, close).trimEnd(' ', '\t', '\n', '\r')
        val added = missing.joinToString(",\n") { "  ${JsonString(it).toJson()}: \"\"" }
        return before + (if (before.endsWith("{")) "\n" else ",\n") + added + "\n" + text.substring(close)
    }

    /**
     * What the variables in a URL were, for a server that could not be reached: a `baseUrl` left
     * at its default is the usual reason, and nothing on screen said so.
     */
    public fun unreachableHint(
        url: String,
        vars: List<Variable>,
    ): String? {
        val byName = vars.associateBy { it.name }
        val used =
            VARIABLE
                .findAll(url)
                .map { it.groupValues[1] }
                .distinct()
                .mapNotNull { byName[it] }
                .toList()
        if (used.isEmpty()) return null
        val parts = used.map { "{{${it.name}}} is ${it.value}, ${it.source.phrase}." }.toMutableList()
        if (used.any { it.source == VariableSource.DEFAULT }) parts += "Set baseUrl in $ENV_FILE if your server listens elsewhere."
        return parts.joinToString(" ")
    }

    public data class Substituted(
        val text: String,
        val missing: List<String>,
    )

    /** Replace `{{name}}` with values; unknown names are left in place and reported. */
    public fun substitute(
        text: String,
        vars: Map<String, String>,
    ): Substituted {
        val missing = LinkedHashSet<String>()
        val out =
            VARIABLE.replace(text) { m ->
                val name = m.groupValues[1]
                if (name in vars) {
                    vars[name] ?: ""
                } else {
                    missing += name
                    m.value
                }
            }
        return Substituted(out, missing.toList())
    }

    public data class Resolved(
        val request: SavedRequest,
        val missing: List<String>,
    )

    public fun resolveRequest(
        r: SavedRequest,
        vars: Map<String, String>,
    ): Resolved {
        val url = substitute(r.url, vars)
        val signals = substitute(r.signals, vars)
        val headers = substitute(r.headers, vars)
        return Resolved(
            r.copy(url = url.text, signals = signals.text, headers = headers.text),
            (url.missing + signals.missing + headers.missing).distinct(),
        )
    }

    /** The request as the Datastar client would send it, one identity for the recent list. */
    public fun requestKey(r: SavedRequest): String = "${r.method} ${r.url}\n${r.signals.trim()}\n${r.headers.trim()}"

    public fun pushRecent(
        recent: List<SavedRequest>,
        r: SavedRequest,
    ): List<SavedRequest> {
        val key = requestKey(r)
        val entry = r.copy(name = r.name.ifEmpty { "${r.method} ${r.url}" })
        return (listOf(entry) + recent.filter { requestKey(it) != key }).take(RECENT_LIMIT)
    }

    /** Parse `Name: value` lines into a header map. */
    public fun parseHeaderLines(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split("\n")) {
            val idx = line.indexOf(':')
            if (idx > 0) out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return out
    }

    /**
     * The signals as compact JSON; text that is not JSON is passed through trimmed.
     *
     * Valid JSON is compacted by dropping the whitespace between its tokens, not by writing it out
     * again: a second writer would spell numbers and escapes its own way, and the VS Code extension
     * has to arrive at the same text.
     */
    public fun compactSignals(signals: String): String {
        val text = signals.trim(' ', '\t', '\n', '\r')
        if (text.isEmpty()) return "{}"
        try {
            JsonParser.parse(text)
        } catch (_: Exception) {
            return text
        }
        val out = StringBuilder(text.length)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (c == '\\') out.append(text[++i])
                if (c == '"') inString = false
            } else if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                out.append(c)
                inString = c == '"'
            }
            i++
        }
        return out.toString()
    }

    /** The URL the Datastar client would open: GET and DELETE carry the signals in the query string. */
    public fun requestUrl(
        url: String,
        method: String,
        signalsJson: String,
    ): String {
        if (method != "GET" && method != "DELETE") return url
        // Taken apart as text: a URL that still holds a `{id}`, a space or an unresolved variable is
        // one java.net.URI refuses, and the rest of it is passed on exactly as it was written.
        val hash = url.indexOf('#')
        val fragment = if (hash < 0) "" else url.substring(hash)
        val beforeFragment = if (hash < 0) url else url.substring(0, hash)
        val kept = beforeFragment.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("datastar=") }
        val query = (kept + ("datastar=" + URLEncoder.encode(wellFormed(signalsJson), Charsets.UTF_8))).joinToString("&")
        return "${beforeFragment.substringBefore('?')}?$query$fragment"
    }

    /** A surrogate without its partner becomes U+FFFD, which is what a browser encodes in its place. */
    private fun wellFormed(s: String): String {
        if (s.none { it.isSurrogate() }) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out.append(c).append(s[++i])
            } else {
                out.append(if (c.isSurrogate()) '\uFFFD' else c)
            }
            i++
        }
        return out.toString()
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** The URL to send, or why it cannot be sent; exactly one of the two is set. */
    public data class SendableUrl(
        val url: String?,
        val error: String?,
    )

    /**
     * The URL of the curl line as the inspector sends it, or why it cannot be sent as written. The
     * text goes out unchanged but for two things every client does alike: the fragment is dropped,
     * and a character beyond ASCII is sent as UTF-8 escapes. Anything curl refuses, globs away or
     * java.net.URI rejects is refused here, and the VS Code extension refuses the same URLs.
     */
    public fun sendableUrl(url: String): SendableUrl {
        fun refuse(message: String) = SendableUrl(null, message)
        val scheme =
            ABSOLUTE.find(url)?.groupValues?.get(1)
                ?: return refuse("Not an absolute URL: $url. Start it with http:// or https://, or with {{baseUrl}}.")
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return refuse("Only http:// and https:// URLs can be sent: $url")
        }
        val target = url.substringBefore('#')
        val start = scheme.length + 3
        val authorityEnd = target.indexOfAny(charArrayOf('/', '?'), start).let { if (it < 0) target.length else it }
        if (authorityEnd == start) return refuse("No host in $url.")
        val out = StringBuilder(target.substring(0, start))
        var i = start
        while (i < target.length) {
            val c = target[i]
            when {
                c <= ' ' || c == '\u007f' -> {
                    return refuse("The URL holds a space or a control character, which cannot be sent. Write a space as %20.")
                }

                c == '{' || c == '}' -> {
                    val param = PATH_PARAM_TEXT.find(target)?.value ?: c.toString()
                    return refuse("The URL still holds $param. Fill in the path parameter before sending.")
                }

                // Brackets belong to an IPv6 host. Elsewhere curl reads them as a range to expand.
                c in NOT_SENDABLE || ((c == '[' || c == ']') && i >= authorityEnd) -> {
                    return refuse("The URL holds $c, which cannot be sent as written. Write it as ${escape(c.toString())}.")
                }

                c == '%' && !(i + 2 < target.length && target[i + 1].isHexDigit() && target[i + 2].isHexDigit()) -> {
                    return refuse("The URL holds a % that starts no escape. Write it as %25.")
                }

                c.code > 0x7f -> {
                    val pair = c.isHighSurrogate() && i + 1 < target.length && target[i + 1].isLowSurrogate()
                    val cp = if (pair) target.codePointAt(i) else c.code
                    // A surrogate without its partner goes out as U+FFFD, as TextEncoder sends it.
                    val char = if (Character.isSurrogate(c) && !pair) "\uFFFD" else String(Character.toChars(cp))
                    if (i < authorityEnd) {
                        return refuse("The host holds $char, which cannot be sent as written. Write the host in its xn-- form.")
                    }
                    out.append(escape(char))
                    i += Character.charCount(cp)
                    continue
                }

                else -> {
                    out.append(c)
                }
            }
            i++
        }
        return SendableUrl(out.toString(), null)
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** UTF-8 escapes in capitals. */
    private fun escape(s: String): String = s.toByteArray(Charsets.UTF_8).joinToString("") { "%%%02X".format(it.toInt() and 0xff) }

    /** A curl command equivalent to what the inspector sends. Variables must already be resolved. */
    public fun toCurl(r: SavedRequest): String {
        val bodyless = r.method == "GET" || r.method == "DELETE"
        val signalsJson = compactSignals(r.signals)
        val parts = mutableListOf("curl", "-N", "-X", r.method)
        parts += shellQuote(requestUrl(r.url, r.method, signalsJson))
        parts += listOf("-H", shellQuote("Accept: text/event-stream"), "-H", shellQuote("Datastar-Request: true"))
        for ((k, v) in parseHeaderLines(r.headers)) parts += listOf("-H", shellQuote("$k: $v"))
        if (!bodyless) parts += listOf("-H", shellQuote("Content-Type: application/json"), "--data", shellQuote(signalsJson))
        return parts.joinToString(" ")
    }

    public data class PathParam(
        val name: String,
        val optional: Boolean,
    )

    /** Path parameters such as `{id}` or Ktor's optional `{id?}`. */
    public fun pathParams(path: String): List<PathParam> =
        PATH_PARAM
            .findAll(path)
            .map {
                PathParam(
                    it.groupValues[1],
                    it.groupValues[2] == "?",
                )
            }.toList()

    /** Fill path parameters; an empty value for an optional parameter removes its segment. */
    public fun fillPath(
        path: String,
        values: Map<String, String>,
    ): String {
        val optional =
            OPTIONAL_SEGMENT.replace(path) { m ->
                val v = values[m.groupValues[1]]
                if (!v.isNullOrEmpty()) "/" + encodeSegment(v) else ""
            }
        return ANY_PARAM.replace(optional) { m -> encodeSegment(values[m.groupValues[1]] ?: "") }
    }

    /** `encodeURIComponent`: percent-encodes everything but the unreserved characters and `!'()*`. */
    private fun encodeSegment(s: String): String =
        URLEncoder
            .encode(s, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    private fun prettyJson(
        v: JsonValue,
        indent: String = "",
    ): String =
        when (v) {
            is JsonObject -> {
                if (v.isEmpty()) {
                    "{}"
                } else {
                    v.entries.joinToString(
                        ",\n",
                        "{\n",
                        "\n$indent}",
                    ) { (k, x) -> "$indent  ${JsonString(k).toJson()}: ${prettyJson(x, "$indent  ")}" }
                }
            }

            is JsonArray -> {
                if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${prettyJson(it, "$indent  ")}" }
            }

            else -> {
                v.toJson()
            }
        }
}
