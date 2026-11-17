package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
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

/** The one signal the log reads back: the id it handed out, so a reopened log keeps it. */
@Serializable
public data class OrdersSignals(
    val visitor: String = "",
)

/** What the server did with one request, as the log shows it. */
public data class LogLine(
    val what: String,
    val status: String,
    val result: String,
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
    private val log = MutableStateFlow<List<LogLine>>(emptyList())

    /** A new list on every line, which is how the log stream tells there is something to send. */
    public val lines: StateFlow<List<LogLine>> = log

    @Volatile internal var lastSeen: Long = 0

    /** The cheap question: what a page is at, without building it. */
    @Synchronized
    public fun etag(page: Int): String = "\"v$version-$page\""

    /** The expensive answer: the rows of a page, and the build it was. */
    @Synchronized
    public fun built(page: Int): OrderPage {
        builds++
        say(LogLine("page $page", "200", "built #$builds"))
        return OrderPage(page, version, builds, rows(page, version))
    }

    @Synchronized
    public fun notBuilt(page: Int) {
        say(LogLine("page $page", "304", "not built"))
    }

    @Synchronized
    public fun change(): Int {
        version++
        say(LogLine("data", "changed", "now version $version"))
        return version
    }

    private fun say(line: LogLine) {
        log.value = (log.value + line).takeLast(LOG_LINES)
    }
}

/**
 * Every visitor's ledger, held in memory and kept small: a ledger nobody used for [idle] is
 * dropped, and past [capacity] the least recently used one goes. The service holds nothing else
 * per visitor, and nothing of theirs but the version they changed.
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

    /** The visitor's ledger, or a new one under a new id when [visitor] is not one this service could have made. */
    @Synchronized
    public fun open(visitor: String?): Ledger {
        val now = clock()
        byVisitor.values.removeIf { now - it.lastSeen > idle.inWholeMilliseconds }
        val id = visitor?.takeIf { ID.matches(it) } ?: newId()
        val ledger = byVisitor.getOrPut(id) { Ledger(id) }
        ledger.lastSeen = now
        while (byVisitor.size > capacity) byVisitor.remove(byVisitor.keys.first())
        return ledger
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
 * The log, streamed: first the id, then the whole list each time a line is added, and an end
 * after [quiet] without one. Every request in the panel adds a line, so two quiet minutes means
 * nobody is using it, and the connection stops keeping the service awake.
 */
public fun orderLog(
    ledger: Ledger,
    quiet: Duration = 2.minutes,
): Flow<DatastarEvent> =
    flow {
        emit(PatchSignals("""{"visitor": "${ledger.visitor}", "logOpen": true}"""))
        emit(
            patchElements(selector = "#orders-log-state", mode = ElementPatchMode.INNER) {
                span { +"Open: each line is written by the server as it answers." }
            },
        )
        var shown: List<LogLine>? = null
        while (true) {
            val lines = withTimeoutOrNull(quiet) { ledger.lines.first { it !== shown } } ?: break
            shown = lines
            emit(patchElements(selector = "#orders-log", mode = ElementPatchMode.INNER) { logLines(lines) })
        }
        emit(
            patchElements(selector = "#orders-log-state", mode = ElementPatchMode.INNER) {
                span { +"Closed after two quiet minutes. Press anything in the panel to open it again." }
            },
        )
        emit(PatchSignals("""{"logOpen": false}"""))
    }

/** One page of orders, as built. */
public data class OrderPage(
    val page: Int,
    val version: Int,
    val build: Int,
    val rows: List<OrderRow>,
)

public data class OrderRow(
    val id: Int,
    val who: String,
    val what: String,
)

private val WHO = listOf("Gorvek", "Hask", "Brann", "Sarn", "Ulla", "Tor", "Mirk", "Ashe", "Kell")
private val WHAT = listOf("swords", "shields", "torches", "ropes", "axes", "cloaks", "lamps", "spears", "horns")

// The version is in the counts, so a change shows in the rows and not only in the caption.
private fun rows(
    page: Int,
    version: Int,
): List<OrderRow> =
    (0 until 3).map {
        val n = (page - 1) * 3 + it
        OrderRow(1040 + n, WHO[n], "${n % 4 + version} ${WHAT[n]}")
    }

/** The table, with the build it came from under it. Its id is what Datastar patches it in by. */
public fun TagConsumer<*>.orderTable(page: OrderPage) {
    div("lt-table-scroll orders__table") {
        id = "orders"
        table("lt-table") {
            caption { +"Page ${page.page} of $ORDER_PAGES, version ${page.version}" }
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
                        td { +row.what }
                    }
                }
            }
        }
        p("lt-paragraph orders__build") {
            attributes["data-size"] = "small"
            +"Built #${page.build}"
        }
    }
}

private fun TagConsumer<*>.logLines(lines: List<LogLine>) {
    if (lines.isEmpty()) {
        p("lt-paragraph") {
            attributes["data-size"] = "small"
            +"Open. Press a page, and what the server did with it shows here."
        }
        return
    }
    ul("orders__lines") {
        for (line in lines) {
            li {
                span { +line.what }
                span("orders__status") { +line.status }
                span { +line.result }
            }
        }
    }
}
