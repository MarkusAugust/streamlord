package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.json.JsonValue
import java.net.URI
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

    /** Insert or replace by name (case-insensitive), keeping the list sorted by name. */
    public fun upsert(
        file: RequestsFile,
        request: SavedRequest,
    ): RequestsFile {
        val others = file.requests.filter { !it.name.equals(request.name, ignoreCase = true) }
        return RequestsFile((others + request).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }))
    }

    public fun remove(
        file: RequestsFile,
        name: String,
    ): RequestsFile = RequestsFile(file.requests.filter { !it.name.equals(name, ignoreCase = true) })

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

    /** The signals as compact JSON; text that is not JSON is passed through trimmed. */
    public fun compactSignals(signals: String): String {
        if (signals.isBlank()) return "{}"
        return try {
            JsonParser.parse(signals).toJson()
        } catch (_: Exception) {
            signals.trim()
        }
    }

    /** The URL the Datastar client would open: GET and DELETE carry the signals in the query string. */
    public fun requestUrl(
        url: String,
        method: String,
        signalsJson: String,
    ): String {
        if (method != "GET" && method != "DELETE") return url
        val uri = URI(url)
        val query = uri.rawQuery
        val encoded = "datastar=" + URLEncoder.encode(signalsJson, Charsets.UTF_8)
        val kept = query?.split('&')?.filter { it.isNotEmpty() && !it.startsWith("datastar=") } ?: emptyList()
        val newQuery = (kept + encoded).joinToString("&")
        val base = url.substringBefore('?').substringBefore('#')
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""
        return "$base?$newQuery$fragment"
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

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
