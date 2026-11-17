package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.html.elements
import io.github.markusaugust.streamlord.html.patchElements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.html.TagConsumer
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How many pages the table has, and how many lines the log keeps. */
public const val ORDER_PAGES: Int = 3
private const val LOG_LINES = 12

/** The Cache-Control of every table, the 200 and the 304 alike; the wire panel shows it as sent. */
public const val ORDERS_CACHE_CONTROL: String = "private, no-cache"

/** The ways the table sorts, the first of them the default. */
public val ORDER_SORTS: List<String> = listOf("order", "who", "what")

/**
 * The one signal the table reads: how it is sorted. The page sends it and no other, so each sort of
 * each page is a URL of its own in the browser's cache, as the caching page shows.
 */
@Serializable
public data class TableSignals(
    val sort: String = ORDER_SORTS.first(),
)

/** A sort the table has, or the default for anything else. */
public fun sortOf(sort: String): String = sort.takeIf { it in ORDER_SORTS } ?: ORDER_SORTS.first()

/** The signals the log reads back: the id it handed out, so a reopened log keeps it, and whether the page shows the wire. */
@Serializable
public data class OrdersSignals(
    val visitor: String = "",
    val wire: Boolean = false,
)

/** What the server did with one request. [n] tells two equal lines apart, so each one reaches the log. */
public data class LogLine(
    val n: Int,
    val what: String,
    val status: String,
    val result: String,
    val body: String,
)

/** The log as the stream sends it: the lines, the last exchange written out for the wire panel, and whether the ledger is gone. */
public data class LogState(
    val lines: List<LogLine> = emptyList(),
    val exchange: String? = null,
    val closed: Boolean = false,
)

/** One answer to a table request: the tag it went out under, its status, and the HTML, or none for a 304 or a 412. */
public data class Answer(
    val etag: String,
    val status: Int,
    val body: String?,
)

/**
 * One visitor's orders: the version their changes have reached, how often a table was built, and
 * the log. Each visitor has their own, so a line in the log is always something they did.
 */
public class Ledger internal constructor(
    public val visitor: String,
) {
    private var version = 1
    private var builds = 0
    private var lines = 0
    private val state = MutableStateFlow(LogState())

    public val log: StateFlow<LogState> = state

    @Volatile internal var lastSeen: Long = 0

    /** Whether a page was ever answered from it; one that never was is the first to go. */
    internal val used: Boolean
        @Synchronized get() = lines > 0

    /** Dropped by [Ledgers]: the log stream on it ends, so the page can open one on a new ledger. */
    @Synchronized
    internal fun close() {
        state.value = state.value.copy(closed = true)
    }

    /**
     * The tag, the check and the build under one lock, so a change cannot fall between the tag
     * and the body it names. [check] answers the status the request's conditional headers ask
     * for; [asked] is the request as the wire panel shows it.
     */
    @Synchronized
    public fun answer(
        page: Int,
        sort: String,
        asked: String,
        check: (etag: String) -> Int,
    ): Answer {
        // No sort in the tag: the browser keeps each sort's URL apart, so the tag only has to change with the data.
        val etag = "\"v$version-$page\""
        val what = "page $page, $sort"
        val status = check(etag)
        if (status != 200) {
            say(what, "$status", "not built", "0 B", asked, response(status, etag, null))
            return Answer(etag, status, null)
        }
        builds++
        val html = elements { orderTable(OrderPage(page, sort, version, orderRows(page, version, sort))) }
        val bytes = html.toByteArray().size
        say(what, "200", "built #$builds", "$bytes B", asked, response(200, etag, bytes))
        return Answer(etag, 200, html)
    }

    @Synchronized
    public fun change(asked: String) {
        version++
        say("data", "changed", "now version $version", "", asked, "200 OK\nContent-Type: application/json\n\n{}")
    }

    private fun say(
        what: String,
        status: String,
        result: String,
        body: String,
        asked: String,
        answered: String,
    ) {
        lines++
        state.value =
            state.value.copy(
                lines = (state.value.lines + LogLine(lines, what, status, result, body)).takeLast(LOG_LINES),
                exchange = "$asked\n\n$answered",
            )
    }
}

private fun response(
    status: Int,
    etag: String,
    bytes: Int?,
): String =
    buildString {
        append(
            when (status) {
                200 -> "200 OK"
                304 -> "304 Not Modified"
                412 -> "412 Precondition Failed"
                else -> "$status"
            },
        )
        if (bytes != null) append("\nContent-Type: text/html; charset=utf-8")
        append("\nCache-Control: $ORDERS_CACHE_CONTROL\nETag: $etag\n\n")
        append(if (bytes != null) "($bytes bytes of HTML)" else "(no body)")
    }

/**
 * Every visitor's ledger, held in memory and kept small: a ledger nobody used for [idle] is
 * dropped, and past [capacity] one goes: the least recently opened that never answered a page, or
 * failing that the least recently used. Only the log makes a ledger, so a request that names an id
 * the service does not hold makes none, and a flood of logs opened and left pushes out its own
 * ledgers before anyone's in use. A dropped ledger ends its log stream.
 */
public class Ledgers(
    private val capacity: Int = 1_000,
    private val idle: Duration = 30.minutes,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Access order, so the first entry is always the least recently used.
    private val byVisitor = LinkedHashMap<String, Ledger>(16, 0.75f, true)

    public val size: Int
        @Synchronized get() = byVisitor.size

    /** The visitor's ledger, made when the service does not hold it; under a new id when [visitor] is not one it could have made. */
    @Synchronized
    public fun open(visitor: String?): Ledger {
        val now = clock()
        forget(now)
        val id = visitor?.takeIf { ID.matches(it) } ?: newId()
        val ledger = byVisitor.getOrPut(id) { Ledger(id) }
        ledger.lastSeen = now
        while (byVisitor.size > capacity) {
            val drop = byVisitor.values.firstOrNull { !it.used && it !== ledger } ?: byVisitor.values.first()
            byVisitor.remove(drop.visitor)
            drop.close()
        }
        return ledger
    }

    /** The visitor's ledger if the service holds it, and nothing made if not. */
    @Synchronized
    public fun find(visitor: String?): Ledger? {
        val now = clock()
        forget(now)
        return visitor?.let { byVisitor[it] }?.also { it.lastSeen = now }
    }

    private fun forget(now: Long) {
        byVisitor.values.removeIf { ledger ->
            (now - ledger.lastSeen > idle.inWholeMilliseconds).also { if (it) ledger.close() }
        }
    }

    private fun newId(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(16)

    private companion object {
        val ID = Regex("[0-9a-f]{16}")
    }
}

/**
 * The log, streamed: first the id if the page does not hold it yet ([sent] is the one it sent), then the lines each time one is added, with the exchange behind
 * it for the wire panel, and an end after [quiet] without one, or as soon as the ledger is dropped.
 * Every request in the panel adds a line, so a quiet stretch means nobody is using it, and the
 * connection stops keeping the service awake. The page tells the log is open by Datastar's own
 * indicator, which ends with the request however it ends, so nothing here has to say so. What was
 * in the log before it opened goes to the log and not to the wire panel, which may be showing
 * another demo's frames.
 */
public fun orderLog(
    ledger: Ledger,
    wire: Boolean,
    sent: String,
    quiet: Duration = 2.minutes,
): Flow<DatastarEvent> =
    flow {
        // The page fetches the first table when the id arrives, so only a new one is sent.
        if (sent != ledger.visitor) emit(PatchSignals("""{"visitor": "${ledger.visitor}"}"""))
        emit(state("Open: each line is written by the server as it answers."))
        var shown: LogState? = null
        while (true) {
            val now = withTimeoutOrNull(quiet) { ledger.log.first { it != shown } } ?: break
            if (now.closed) {
                emit(state("The server has let this panel go. Press anything in it to open a new log."))
                return@flow
            }
            emit(patchElements(selector = "#orders-log", mode = ElementPatchMode.INNER) { logLines(now.lines) })
            if (wire && shown != null && now.exchange != null) {
                emit(patchElements(selector = "#wire", mode = ElementPatchMode.INNER) { pre("wire__frame") { +now.exchange } })
            }
            shown = now
        }
        emit(state("Closed after ${quiet.inWholeMinutes} quiet minutes. Press anything in the panel to open it again."))
    }

private fun state(text: String): DatastarEvent =
    patchElements(selector = "#orders-log-state", mode = ElementPatchMode.INNER) {
        span { +text }
    }

/** One page of orders, as built. */
public data class OrderPage(
    val page: Int,
    val sort: String,
    val version: Int,
    val rows: List<OrderRow>,
)

public data class OrderRow(
    val id: Int,
    val who: String,
    val count: Int,
    val item: String,
)

private val WHO = listOf("Gorvek", "Hask", "Brann", "Sarn", "Ulla", "Tor", "Mirk", "Ashe", "Kell")
private val ITEM = listOf("swords", "shields", "torches", "ropes", "axes", "cloaks", "lamps", "spears", "horns")

/**
 * A page of the table, sorted as a whole and then cut, as a real one is. The version is in the
 * counts, so a change shows in the rows and not only in the caption.
 */
internal fun orderRows(
    page: Int,
    version: Int,
    sort: String,
): List<OrderRow> {
    // From two up, so the plural always fits.
    val all = WHO.indices.map { OrderRow(1040 + it, WHO[it], it % 4 + version + 1, ITEM[it]) }
    val sorted =
        when (sort) {
            "who" -> all.sortedBy { it.who }
            "what" -> all.sortedBy { it.item }
            else -> all
        }
    return sorted.drop((page - 1) * 3).take(3)
}

/**
 * The table. Its id is what Datastar patches it in by. It holds nothing the tag does not name, so
 * the same tag is always the same bytes, as a strong ETag promises; how often it was built is the
 * log's to say.
 */
public fun TagConsumer<*>.orderTable(page: OrderPage) {
    div("lt-table-scroll") {
        id = "orders"
        table("lt-table") {
            // Page and sort are in the panel's toolbar; the version is shown nowhere else.
            caption { +"Version ${page.version}" }
            thead {
                tr {
                    th { +"Order" }
                    th { +"Who" }
                    th { +"What" }
                }
            }
            tbody {
                for (row in page.rows) {
                    tr {
                        td { +"#${row.id}" }
                        td { +row.who }
                        td { +"${row.count} ${row.item}" }
                    }
                }
            }
        }
    }
}

/** What the table and the change answer for an id the service no longer holds. */
public fun TagConsumer<*>.forgotten() {
    div {
        id = "orders"
        p("lt-paragraph") {
            attributes["data-size"] = "small"
            +"The server had let this panel go, and has opened it again. Press the page once more."
        }
    }
}

private fun TagConsumer<*>.logLines(lines: List<LogLine>) {
    if (lines.isEmpty()) {
        p("lt-paragraph") {
            attributes["data-size"] = "small"
            +"Press a page, and what the server did with it shows here."
        }
        return
    }
    ul("orders__lines") {
        for (line in lines) {
            li {
                span { +line.what }
                span("orders__status") { +line.status }
                span { +line.result }
                span { +line.body }
            }
        }
    }
}
