package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import java.net.URLEncoder

/**
 * The request Datastar 1.0.4's `@get` and its kin send with the default JSON content type
 * (fetch.ts): the client's headers, and the page's signals, in the body or, for a method without
 * one, in the `datastar` query parameter. The test client and the editors' request inspector send
 * it the same way; `editors/vscode/test/fixtures/request-urls.json` holds the URL to it.
 */
@InternalStreamlordApi
public object ClientRequest {
    private val PERCENT_BYTE = Regex("%([0-9A-Fa-f]{2})")

    /**
     * Whether [method] sends the signals in the query rather than in a body: `GET` and `DELETE`
     * (`methodSupportsRequestBody` in fetch.ts). The client passes its methods in capitals; this
     * reads one in any case, as a method typed by hand in the inspector may be.
     */
    public fun bodyless(method: String): Boolean = method.uppercase() in DatastarProtocol.BODYLESS_METHODS

    /** The headers the client sends with [method] (fetch.ts:81-88), before the page's own. */
    public fun headers(method: String): Map<String, String> =
        buildMap {
            put("Accept", DatastarProtocol.ACCEPT)
            put(DatastarProtocol.Headers.REQUEST, "true")
            if (!bodyless(method)) put("Content-Type", DatastarProtocol.CONTENT_TYPE_JSON)
        }

    /**
     * [url] with [signalsJson] as its `datastar` parameter, as `queryParams.set` puts it
     * (fetch.ts:149): where the first one stands, written with a value or without, the others
     * dropped, or appended when there is none. A name is read as a form reads it, `+` as a space
     * and `%XX` as a byte, so `data%73tar` is the parameter too. The rest of the URL is taken apart
     * as text and kept as written, which a server reads the same as the client's re-serialised
     * query: a URL that still holds a `{id}` or a space is one java.net.URI would refuse.
     */
    public fun withSignals(
        url: String,
        signalsJson: String,
    ): String {
        val hash = url.indexOf('#')
        val fragment = if (hash < 0) "" else url.substring(hash)
        val beforeFragment = if (hash < 0) url else url.substring(0, hash)

        // A byte past ASCII stays one character, which is enough to tell the name from datastar.
        fun ours(p: String) =
            PERCENT_BYTE.replace(p.substringBefore('=').replace('+', ' ')) {
                it.groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            } == DatastarProtocol.SIGNALS_PARAMETER
        val pairs = beforeFragment.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        val param = DatastarProtocol.SIGNALS_PARAMETER + "=" + URLEncoder.encode(wellFormed(signalsJson), Charsets.UTF_8)
        val first = pairs.indexOfFirst(::ours)
        val query =
            if (first < 0) {
                pairs + param
            } else {
                pairs.filterIndexed { k, p -> k == first || !ours(p) }.map { if (ours(it)) param else it }
            }.joinToString("&")
        return "${beforeFragment.substringBefore('?')}?$query$fragment"
    }

    /** A surrogate without its partner becomes U+FFFD, which is what a browser encodes in its place. */
    public fun wellFormed(s: String): String {
        if (s.none { it.isSurrogate() }) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out.append(c).append(s[++i])
            } else {
                out.append(if (c.isSurrogate()) '�' else c)
            }
            i++
        }
        return out.toString()
    }
}
