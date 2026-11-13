@file:OptIn(ExperimentalJsExport::class)

package io.github.markusaugust.streamlord.editor

import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.RunningServer
import io.github.markusaugust.streamlord.analysis.ServerLog
import io.github.markusaugust.streamlord.analysis.SseMessage
import io.github.markusaugust.streamlord.analysis.SseParser
import io.github.markusaugust.streamlord.analysis.decodeDatastar
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.json.SignalPatchException
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import io.github.markusaugust.streamlord.analysis.RequestsEdit as AnalysedEdit
import io.github.markusaugust.streamlord.analysis.SavedRequest as AnalysedRequest
import io.github.markusaugust.streamlord.analysis.findRoutes as analysedRoutes

// The Stream Inspector's half of the port: routes, saved requests and their variables, the request
// as the client sends it, the stream as the client reads it, and what a server's log says.

// ---- Routes and saved requests -----------------------------------------------------------------

@JsExport
interface SavedRequest {
    val name: String
    val url: String
    val method: String

    /** Signals as JSON text, as the user wrote them. */
    val signals: String

    /** One `Name: value` a line. */
    val headers: String
}

/** A Ktor or Spring route in a Kotlin file, with the request the inspector opens for it. */
@JsExport
interface Route {
    val offset: Int
    val method: String
    val path: String

    /** The parameters the route reads and does not need. */
    val optional: Array<String>
    val request: SavedRequest
}

@JsExport
interface RequestsFile {
    val requests: Array<SavedRequest>
    val error: String?

    /** Why each entry that was not read was left out. */
    val skipped: Array<String>
}

/** The requests file after an edit: its new [text], or the [error] that kept it from being written. */
@JsExport
interface RequestsEdit {
    val text: String?
    val error: String?
}

@JsExport
interface InspectorFiles {
    val requests: String
    val env: String
}

private fun plain(r: AnalysedRequest): SavedRequest =
    plain("name" to r.name, "url" to r.url, "method" to r.method, "signals" to r.signals, "headers" to r.headers)

// A request from a saved state or the inspector's page may lack a field a later version added.
private fun analysed(r: SavedRequest): AnalysedRequest {
    val o: dynamic = r
    return AnalysedRequest(
        (o.name as String?) ?: "",
        (o.url as String?) ?: "",
        (o.method as String?) ?: "GET",
        (o.signals as String?) ?: "",
        (o.headers as String?) ?: "",
    )
}

private fun plain(edit: AnalysedEdit): RequestsEdit = plainWithNulls("text" to edit.text, "error" to edit.error)

@JsExport
fun findRoutes(src: String): Array<Route> =
    analysedRoutes(src)
        .map { route ->
            plain<Route>(
                "offset" to route.offset,
                "method" to route.method,
                "path" to route.path,
                "optional" to Requests.routeOptional(route).plain(),
                "request" to plain(AnalysedRequest("", Requests.routeUrl(route), route.method, "", Requests.routeHeaders(route))),
            )
        }.toTypedArray()

/** Where the inspector keeps its requests and its variables, from the workspace folder. */
@JsExport
fun inspectorFiles(): InspectorFiles = plain("requests" to Requests.REQUESTS_FILE, "env" to Requests.ENV_FILE)

/** Why [path] cannot be the requests file, or null. */
@JsExport
fun requestsPathProblem(path: String): String? = Requests.requestsPathProblem(path, "workspace")

/** The requests in the text of the file at [path]; a file that is not there holds none. */
@JsExport
fun parseRequestsFile(
    text: String?,
    path: String,
): RequestsFile {
    val file = Requests.parseRequestsFile(text, path)
    return plain("requests" to file.requests.map(::plain).toTypedArray(), "error" to file.error, "skipped" to file.skipped.plain())
}

@JsExport
fun saveRequest(
    text: String?,
    request: SavedRequest,
    path: String,
): RequestsEdit = plain(Requests.saveRequest(text, analysed(request), path))

@JsExport
fun removeRequest(
    text: String?,
    name: String,
    path: String,
): RequestsEdit = plain(Requests.removeRequest(text, name, path))

/** [recent] with [request] first, once, and no longer than the inspector shows. */
@JsExport
fun pushRecent(
    recent: Array<SavedRequest>,
    request: SavedRequest,
): Array<SavedRequest> = Requests.pushRecent(recent.map(::analysed), analysed(request)).map(::plain).toTypedArray()

@JsExport
fun newRequestLabel(
    saved: Int,
    recent: Int,
): String = Requests.newRequestLabel(saved, recent)

// ---- Variables ---------------------------------------------------------------------------------

@JsExport
interface Variable {
    val name: String
    val value: String

    /** `default`, `env` or `running`. */
    val source: String

    /** The debug session a running server's address came from. */
    val origin: String?
}

/** The variables a request may name, and what reading them found wrong. */
@JsExport
interface Environment {
    val vars: Array<Variable>
    val errors: Array<String>

    /** Each variable's value by its name. */
    val values: dynamic

    /** The variables as the inspector lists them. */
    val described: String
}

@JsExport
interface ResolvedRequest {
    val request: SavedRequest
    val errors: Array<String>

    /** The keys the request needs that the env file does not set. */
    val unset: Array<String>
}

@JsExport
interface VariableCompletion {
    val from: Int
    val to: Int
    val items: Array<Variable>
}

private fun plain(v: Requests.Variable): Variable =
    plain("name" to v.name, "value" to v.value, "source" to v.source.name.lowercase(), "origin" to v.origin)

private fun source(v: Variable): Requests.VariableSource? = Requests.VariableSource.entries.firstOrNull { it.name.lowercase() == v.source }

/** A source that is none of the three is no default and no running server, which leaves the file. */
private fun analysed(vars: Array<Variable>): List<Requests.Variable> =
    vars.map { Requests.Variable(it.name, it.value, source(it) ?: Requests.VariableSource.ENV, it.origin) }

private fun running(
    url: String?,
    name: String?,
): RunningServer? = url?.let { RunningServer(it, name ?: "") }

/**
 * The variables from the text of the env file, which may be missing, over [defaultUrl]; a server
 * the editor saw start, at [runningUrl], gives `baseUrl` where the file sets none. A file with
 * errors gives none of its own.
 */
@JsExport
fun environment(
    text: String?,
    defaultUrl: String,
    runningUrl: String?,
    runningName: String?,
): Environment {
    val env = Requests.environment(text, defaultUrl, running(runningUrl, runningName))
    val vars = env.vars
    return plain(
        "vars" to vars.map(::plain).toTypedArray(),
        "errors" to env.errors.plain(),
        "values" to record(Requests.variableValues(vars)),
        "described" to Requests.describeVariables(vars),
    )
}

/** The env file's text with [keys] added, `baseUrl` as [baseUrl], or null when it is not a JSON object. */
@JsExport
fun withEnvVariables(
    text: String?,
    keys: Array<String>,
    baseUrl: String,
): String? = Requests.withEnvVariables(text, keys.toList(), baseUrl)

@JsExport
fun withBaseUrl(
    text: String?,
    url: String,
): String? = Requests.withBaseUrl(text, url)

/** [request] with each `{{name}}` filled in from [vars]. */
@JsExport
fun resolveRequest(
    request: SavedRequest,
    vars: Array<Variable>,
): ResolvedRequest {
    val resolved = Requests.resolveRequest(analysed(request), analysed(vars))
    return plain("request" to plain(resolved.request), "errors" to resolved.errors.plain(), "unset" to resolved.unset.plain())
}

/** The variables to offer at [caret] in [text], the text of the field [field]: `url`, `signals` or `headers`. */
@JsExport
fun variableCompletions(
    text: String,
    caret: Int,
    field: String,
    vars: Array<Variable>,
): VariableCompletion? {
    val known = Requests.Field.entries.firstOrNull { it.name.lowercase() == field }
    // A field that is none of the three takes what the URL takes but `baseUrl`: the params.
    val offered = analysed(vars).filter { known != null || it.name != "baseUrl" }
    return Requests.variableCompletions(text, caret, known ?: Requests.Field.URL, offered)?.let {
        plain("from" to it.from, "to" to it.to, "items" to it.items.map(::plain).toTypedArray())
    }
}

@JsExport
fun unreachableHint(
    url: String,
    vars: Array<Variable>,
    runningUrl: String?,
    runningName: String?,
): String? = Requests.unreachableHint(url, analysed(vars), running(runningUrl, runningName))

@JsExport
fun baseUrlSuggestion(
    vars: Array<Variable>,
    runningUrl: String?,
    runningName: String?,
): String? =
    // Offered only over a `baseUrl` the file is known to set.
    if (vars.any {
            it.name == "baseUrl" && source(it) == null
        }
    ) {
        null
    } else {
        Requests.baseUrlSuggestion(analysed(vars), running(runningUrl, runningName))
    }

// ---- The request as the client sends it --------------------------------------------------------

/** What to hand `fetch`, or the [error] that keeps the request from being sent. */
@JsExport
interface StreamRequest {
    val url: String?
    val method: String?

    /** A header's value by its name. */
    val headers: dynamic
    val body: String?
    val error: String?
}

/** How a response is read: as a [stream] of events, or as a body, [unread] when it is not worth reading. */
@JsExport
interface ResponseReading {
    val stream: Boolean
    val unread: String?

    /** How many bytes of a body are read, and what is written after one cut there. */
    val limit: Int
    val cut: String
}

@JsExport
fun toCurl(request: SavedRequest): String = Requests.toCurl(analysed(request))

/** The signals as they go out: the JSON without the white space around it. */
@JsExport
fun compactSignals(signals: String): String = Requests.compactSignals(signals)

/** The headers written one a line, each value by its name. */
@JsExport
fun parseHeaderLines(text: String): dynamic = record(Requests.parseHeaderLines(text))

/**
 * The request Datastar's client would send: the signals in the query for a GET or a DELETE and in
 * the body otherwise, its own headers, and [headers], an object of the user's, over them.
 */
@JsExport
fun streamRequest(
    url: String,
    method: String,
    signals: String?,
    headers: dynamic,
): StreamRequest {
    val request = Requests.streamRequest(url, method, signals ?: "", entries(headers))
    request.error?.let { return plain("error" to it) }
    return plain("url" to request.url, "method" to request.method, "headers" to record(request.headers), "body" to request.body)
}

@JsExport
fun responseReading(
    status: Int,
    contentType: String?,
): ResponseReading =
    plain(
        "stream" to Requests.readsAsStream(status, contentType),
        "unread" to Requests.unreadBody(status, contentType),
        "limit" to Requests.NON_STREAM_LIMIT,
        "cut" to Requests.NON_STREAM_CUT,
    )

/** What to say under an answer of [status] that is no stream, or null. */
@JsExport
fun emptyBodyHint(
    status: Int,
    body: String,
): String? = Requests.emptyBodyHint(status, body)

// ---- The stream as the client reads it ---------------------------------------------------------

@JsExport
interface DatastarFrame {
    val event: String
    val id: String?
    val retry: Int?

    /** Each argument's value by its name: `selector`, `elements`, `signals`. */
    val args: dynamic
    val raw: String

    /** When it was read, in milliseconds since 1970. */
    val receivedAt: Double
}

/** One thing a stream said: a [comment], or a [frame]. */
@JsExport
interface StreamEvent {
    val comment: String?
    val frame: DatastarFrame?
}

/** The store after a signal patch, and the [error] the patch ended in; [refused] where Streamlord refuses it on purpose. */
@JsExport
interface PatchedStore {
    /** The store as JSON text. */
    val store: String
    val error: String?
    val refused: Boolean
}

/** Reads a response body as Datastar's client reads it, chunk by chunk as it arrives. */
@JsExport
class StreamReader {
    private val parser = SseParser()

    private fun shown(message: SseMessage): StreamEvent {
        if (message.isComment) return plain("comment" to message.comments.joinToString("\n"))
        val frame = decodeDatastar(message, 0)
        return plain(
            "frame" to
                plainWithNulls<DatastarFrame>(
                    "event" to frame.event,
                    "id" to frame.id,
                    "retry" to frame.retry,
                    "args" to record(frame.args),
                    "raw" to frame.raw,
                    "receivedAt" to js("Date.now()"),
                ),
        )
    }

    fun feed(chunk: String): Array<StreamEvent> = parser.feed(chunk).map(::shown).toTypedArray()

    /** What the stream held when it ended without a blank line after it. */
    fun finish(): Array<StreamEvent> = listOfNotNull(parser.finish()).map(::shown).toTypedArray()
}

/**
 * The store [storeJson] after the client has folded the signal patch [patchJson] into it. A patch
 * the client fails on keeps the keys before the one it failed at, and so does the store given back.
 */
@JsExport
fun applySignalPatch(
    storeJson: String,
    patchJson: String,
    onlyIfMissing: Boolean,
): PatchedStore =
    try {
        val store = mergePatchAsClient(JsonParser.parseObject(storeJson, NO_LIMIT), JsonParser.parse(patchJson, NO_LIMIT), onlyIfMissing)
        plain("store" to JsonWriter.write(store, NO_LIMIT), "refused" to false)
    } catch (e: SignalPatchException) {
        plain("store" to JsonWriter.write(e.store, NO_LIMIT), "error" to e.message, "refused" to e.refused)
    }

// The client nests as deep as a stream says. The limit the SDK reads a request under is its own.
private const val NO_LIMIT = Int.MAX_VALUE

// ---- A server's log ----------------------------------------------------------------------------

/** [line] without the escape sequences a coloured console log holds. */
@JsExport
fun plainLogLine(line: String): String = ServerLog.plain(line)

/** The base URL a server announces in [line], or null. */
@JsExport
fun serverStartedAt(line: String): String? = ServerLog.startedAt(line)

/** Whether [line] is a warning or an error, which belongs under a request that failed. */
@JsExport
fun isServerProblem(line: String): Boolean = ServerLog.isProblem(line)
