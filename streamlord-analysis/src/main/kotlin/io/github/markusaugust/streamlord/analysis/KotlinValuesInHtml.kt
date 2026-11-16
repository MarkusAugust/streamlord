package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.DecodedValue
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.ValueLanding
import io.github.markusaugust.streamlord.core.domain.refusedUrlScheme
import io.github.markusaugust.streamlord.core.domain.urlHostFixedBy
import io.github.markusaugust.streamlord.core.domain.urlSchemeFixedBy
import io.github.markusaugust.streamlord.core.domain.valueLandings

/*
 * A Kotlin value written into HTML, and calls to interpolate: where each value lands as core reads
 * the markup, what interpolate would do with it, and the message and the fixes for each place.
 */

/**
 * The attribute values of a markup [view], each read and decoded once, however many values are
 * written into it: whether a value written at an offset lands inside a JavaScript string.
 */
private class ExpressionValues(
    private val view: String,
) {
    private class Read(
        val value: String,
        val decoded: DecodedValue,
        val quotes: QuoteScan,
    )

    private val read = HashMap<Int, Read>()

    private fun at(valueStart: Int): Read =
        read.getOrPut(valueStart) {
            val quote = view.getOrNull(valueStart - 1) ?: '"'
            val valueEnd = view.indexOf(quote, valueStart)
            val value = view.substring(valueStart, if (valueEnd < 0) view.length else valueEnd)
            val decoded = decodeEntities(value)
            Read(value, decoded, QuoteScan(decoded.text))
        }

    fun value(valueStart: Int): String = at(valueStart).value

    /** Whether the decoded text before [offset] in the value leaves a JavaScript string open. No character reference runs on into a placeholder. */
    fun inString(
        valueStart: Int,
        offset: Int,
    ): Boolean {
        val r = at(valueStart)
        val map = r.decoded.map
        val decodedOffset =
            map
                .asList()
                .binarySearch { if (it < offset) -1 else 1 }
                .let { -it - 1 }
                .coerceAtMost(r.decoded.text.length)
        return r.quotes.at(decodedOffset) != null
    }
}

// A name, a property chain or a number literal, which takes a call after it without brackets.
private val SIMPLE_RECEIVER = regex("""[\p{L}_][\p{L}\p{Nd}_]*(?:\.[\p{L}_][\p{L}\p{Nd}_]*)*|\p{Nd}[\p{L}\p{Nd}_]*""")

/** A character, a string literal without a template or a comment in a value: text of the value, and no code of it. */
private val NO_CODE = regex("""'(?:\\.|[^'\\])'|\"(?:\\.|[^"\\$])*\"|/\*[\s\S]*?\*/|//[^\n]*""")

/**
 * A `this` without a label, or a call without a receiver of what everything has, `toString()`, `let { }` and their
 * like: in the block of interpolate each means the block.
 */
private val OWN_THIS =
    regex(
        """(?<![\p{L}\p{Nd}_.@`])(?:this(?![\p{L}\p{Nd}_@`])|""" +
            """javaClass(?![\p{L}\p{Nd}_`])|(?:toString|hashCode|equals|let|also|apply|run|takeIf|takeUnless)\s*[({])""",
    )
private val END_TAG_ATTRIBUTES = regex("""</[^\t\n\u000C\r />]*[\t\n\u000C\r /][^>]*""")

/** The characters that end an unquoted attribute value, and the space before one: ASCII only, as HTML has it. */
private const val UNQUOTED_STOP = " \t\n\u000C\r\u000B\"'=<>`"

/** Where escaping is all a value needs, so that `interpolate(...)` takes it. */
private val ESCAPED_POSITIONS = setOf(Position.TEXT, Position.ATTRIBUTE, Position.URL)

/** Why no rewrite is offered where the value alone stands in its way. */
private const val OWN_MARKUP_NOTE = " Marking the values is not offered here: it would escape this value, which may be markup you built."

/** What interpolate does with a URL whose value decides the scheme. */
private const val REFUSES_SCHEME = "interpolate { }, which refuses a scheme that runs"

private const val CODE_URL_ADVICE =
    "Start the value with a path such as /js/ or a whole origin such as https://cdn.example/, so that the value only picks a file there"

/** Where a markup's placeholders land, read with the text of the known ones in place. */
private class ValueReading(
    val landings: List<ValueLanding>,
    /** What the markup reads from where the value of placeholder `j`'s attribute starts to where placeholder `to` starts, each known text in place. */
    val prefix: (j: Int, to: Int) -> String,
)

/** A list of plain string literals, as `interpolate` takes its prefixes: `listOf("data-x-")`. */
private val PREFIX_LIST = regex("""(?:listOf|mutableListOf|arrayListOf)\s*\(\s*((?:"[^"\\$]*"\s*,\s*)*(?:"[^"\\$]*"\s*,?\s*)?)\)""")
private val PLAIN_LITERAL = regex(""""([^"\\$]*)"""")

/** What stands before the URL in the content of a refresh: a delay, a separator and `url=`, with a quote where one is written. */
private val REFRESH_BEFORE_URL = regex("""\s*\d+(?:\.\d*)?\s*[;,]\s*(?:[Uu][Rr][Ll]\s*=\s*)?['"]?""")
private val CONST_VAL_BEFORE = regex("""\bconst\s+val\s+[^=\n]*=\s*\z""")
private val ANNOTATION_BEFORE = regex("""@[\p{L}_][\p{L}\p{Nd}_.:]*\s*\z""")

private val FORMAT_SPECIFIER = regex("""%[-#+0,(]*\d*(?:\.\d+)?[dfxXoeEgGcb](?![A-Za-z])""")

// A run of dollars, and the dollar and brace that open a template.
private val DOLLAR_RUNS = regex("""\$+""")
private val BRACED_TEMPLATE = regex("""\$\{""")

/** The DSL helper that says the same as a whole-string expression, when there is one, by its name. */
private val HELPERS: List<Pair<Regex, String>> =
    listOf(
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)$""") to "signal",
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)\+\+$""") to "increment",
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)--$""") to "decrement",
        regex("""^!\$([A-Za-z_][A-Za-z0-9_.]*)$""") to "not",
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*) = !\$\1$""") to "toggle",
    )

/**
 * A string that holds HTML but is not handed straight to a Streamlord call: a function that
 * returns markup, a `val` with a fragment. Nothing is known about how it will be patched, so
 * only the attributes are checked, as in a template file; ids and completeness are not.
 */
internal fun Analyzer.checkFreeHtmlString(
    s: KotlinString,
    opts: Reading,
    src: String,
    hyphenated: Set<String>,
    inner: Boolean,
): Judged {
    val view = markupView(src, s)
    val judged = judgeValues(s, src, opts, view, inner)
    return Judged(judged.issues + mapIssues(s, src, analyzeHtml(view, opts, hyphenated, templates = false)), judged.rewrite)
}

/**
 * The prefixes an `interpolate(prefixes, format, ...)` call is given, which it alone reads, where
 * they are written as a list of plain literals; null where none are given or the text cannot tell them.
 */
private fun Analyzer.literalPrefixes(
    site: CallSite,
    formatArg: Arg,
): List<String>? {
    val arg = site.named("prefixes") ?: site.positional.firstOrNull()?.takeIf { it.start < formatArg.start } ?: return null
    val list = PREFIX_LIST.matchEntire(arg.text.trim()) ?: return null
    return PLAIN_LITERAL.findAll(list.groupValues[1]).map { it.groupValues[1] }.toList()
}

/** The format handed to `interpolate`: its first argument or `format =`, a literal or a `+` chain that starts with one. */
internal fun Analyzer.interpolateFormat(
    site: CallSite,
    src: String,
): Pair<Arg, KotlinString>? {
    val lex = lex(src)
    // The prefixes of an aliased bundle may come first, as a list; the format is the first string.
    val leading = site.positional.firstOrNull()
    val prefixed = leading != null && leading.string == null && lex.strings.none { it.start == leading.start }
    val arg = site.named("format") ?: (if (prefixed) site.positional.getOrNull(1) else null) ?: leading ?: return null
    arg.string?.let { return if (it.unterminated) null else arg to it }
    val first = lex.strings.firstOrNull { it.start == arg.start } ?: return null
    // A trim call leaves each placeholder where it stood.
    if (!first.unterminated && trimAfter(src, first)?.text?.let { first.end + it.length } == arg.end) return arg to first
    val chain = chainFrom(src, first, lex.mask)?.takeIf { it.end == arg.end } ?: return null
    return arg to chain
}

/**
 * A call to `interpolate`, judged as it will run. A Kotlin template in the format is filled in
 * before `interpolate` sees the markup, so it is neither escaped nor refused. The `%s` placeholders
 * are counted against the values, and each is judged by where it lands and what the text shows
 * the value to be: core's `admits`, so an error only where `interpolate` throws for certain.
 * The format is read as HTML like any free string; it is not a patch, so no id is asked for.
 */
internal fun Analyzer.checkInterpolateSite(
    site: CallSite,
    formatArg: Arg,
    s: KotlinString,
    src: String,
    opts: Reading,
    hyphenated: Set<String>,
): List<Issue> {
    val placeholders = percentPlaceholders(s.text)
    val (format, at) = withPercentPlaceholders(s, placeholders)
    // Each value as the code writes it, without a comment after it, which the editor's range leaves out too.
    // What stands before the format is the prefixes of an aliased bundle, and no value.
    val values = site.args.filter { it.start > formatArg.start && it.named == null }.map { uncommented(src, it) }
    // The values one by one, as the vararg overload takes them: no spread and nothing by name.
    val plain = site.args.all { it.named == null || it.start == formatArg.start } && values.none { it.text.startsWith("*") }
    val known = values.map { opts.valueFact?.invoke(it.start, it.end) }
    val kinds =
        values.mapIndexed { k, it ->
            if (known[k].isOther()) null else known[k].argKind() ?: argKind(src, it.text, it.string != null, s.start)
        }
    val issues = ArrayList<Issue>()
    // The values are always the values: a List among them is one, written as a template writes it.
    val collections =
        if (plain) {
            values.mapIndexedNotNull { k, v ->
                collectionElements(v.text)?.takeIf { it.isNotEmpty() }?.let {
                    k to
                        it
                }
            }
        } else {
            emptyList()
        }

    fun writtenOut(of: List<Pair<Int, List<String>>>) =
        Fix(
            "Pass the elements as the values",
            values[of[0].first].start,
            values[of[0].first].end,
            of[0].second.joinToString(", "),
            of.drop(1).map { (k, elements) -> Edit(values[k].start, values[k].end, elements.joinToString(", ")) },
        )
    // Where the elements would fill the placeholders, the count is the list's doing: the error says so.
    val spread = collections.isNotEmpty() && placeholders.size == values.size + collections.sumOf { it.second.size - 1 }
    if (plain && placeholders.size != values.size) {
        val h = placeholders.size
        val v = values.size
        // `%d` as String.format writes it: interpolate knows only %s.
        val specifier = FORMAT_SPECIFIER.find(s.text.replace("%%", "  "))?.value
        val advice =
            if (specifier != null) {
                "Only %s is a placeholder, whatever the value: write %s in place of $specifier."
            } else {
                "Write %% for a per cent sign that is not a placeholder."
            }
        issues +=
            Issue(
                site.nameStart,
                site.nameStart + site.name.length,
                "interpolate throws IllegalArgumentException here: the markup has " +
                    "$h %s ${if (h == 1) "placeholder" else "placeholders"} and " +
                    "$v ${if (v == 1) "value is" else "values are"} given. " +
                    if (spread) {
                        val named = collections.joinToString(" and ") { shortened(values[it.first].text) }
                        "$named ${if (collections.size == 1) "is one value" else "are one value each"}: " +
                            "write ${if (collections.size == 1) "its" else "their"} elements out to pass them as the values."
                    } else {
                        advice
                    },
                Severity.ERROR,
                "interpolate-placeholders",
                fixes = if (spread) listOf(writtenOut(collections)) else emptyList(),
            )
    } else {
        for (c in collections) {
            val v = values[c.first]
            issues +=
                Issue(
                    v.start,
                    v.end,
                    "interpolate takes ${shortened(v.text)} as one value and writes it whole, in brackets, escaped like any text. " +
                        "Write the elements out to pass them as the values.",
                    Severity.HINT,
                    "interpolate-placeholders",
                    fixes = listOf(writtenOut(listOf(c))),
                )
        }
    }
    val mapped = plain && placeholders.size == values.size
    return judgeInterpolated(
        s,
        format,
        at,
        values,
        kinds,
        mapped,
        literalPrefixes(site, formatArg),
        formatArg,
        src,
        opts,
        hyphenated,
        issues,
    )
}

/**
 * The block form, `interpolate { "<p>${+name}</p>" }`: a template that opens with `+` is a value interpolate takes where it
 * stands, judged as the value of a `%s` there is; any other template Kotlin fills in before interpolate sees the markup.
 * [prefixes] are those the call is given, null for none, and [block] is what stands in the block's braces.
 */
internal fun Analyzer.checkInterpolateBlock(
    s: KotlinString,
    prefixes: List<String>?,
    block: String,
    src: String,
    opts: Reading,
    hyphenated: Set<String>,
): List<Issue> {
    val all = s.interpolations.filter { markedValue(it) != null }
    val read =
        all.map { ip ->
            val value = markedValue(ip)!!
            val start = src.indexOf(value, src.indexOf('+', ip.start) + 1)
            Arg(start, start + value.length, null, value, null)
        }
    val facts = read.map { opts.valueFact?.invoke(it.start, it.end) }
    val told =
        read.mapIndexed { k, it ->
            if (facts[k].isOther()) null else facts[k].argKind() ?: argKind(src, it.text, it.text.startsWith("\""), s.start)
        }
    // A + on a number is Kotlin's own and marks nothing: the number is written as it is, as from any template.
    val marks = all.indices.filter { told[it] != ArgKind.NUMBER }
    // A name set to a mark in the block, `val title = +name`, is the mark where it is written.
    val set = s.interpolations.filter { it.kind == InterpolationKind.SIMPLE && setToMark(block, it.text) }
    val format = s.copy(interpolations = s.interpolations - all.toSet() - set.toSet())
    return judgeInterpolated(
        s,
        format,
        marks.map { all[it].decodedStart },
        marks.map { read[it] },
        marks.map { told[it] },
        true,
        prefixes,
        null,
        src,
        opts,
        hyphenated,
        ArrayList(),
        marks.map { all[it].start until all[it].end },
    )
}

private fun setToMark(
    block: String,
    name: String,
): Boolean = regex("""(?<![\p{L}\p{Nd}_])${Regex.escape(name)}\s*=\s*\+(?!\+)""").containsMatchIn(block)

/**
 * What a template of the block form, or a value joined with `+` in it, marks with `+`, as written and without
 * parentheses around all of it; null where nothing is marked.
 */
internal fun markedValue(ip: Interpolation): String? =
    ip.text
        .trim()
        .takeIf { ip.kind != InterpolationKind.SIMPLE && it.startsWith("+") && !it.startsWith("++") }
        ?.drop(1)
        ?.trim()
        ?.let { if (it.startsWith("(") && closing(it, depths(it), 0) == it.length) it.substring(1, it.length - 1).trim() else it }
        ?.takeIf { it.isNotEmpty() }

/**
 * The values of an interpolate, each judged by where it lands in [format]: those at [at], which interpolate takes, against
 * core's `admits`, and the templates left in the format, which Kotlin fills in first. [formatArg] is the format of the
 * placeholder form, null for the block form, where a value is taken by its mark.
 */
private fun Analyzer.judgeInterpolated(
    s: KotlinString,
    format: KotlinString,
    at: List<Int>,
    values: List<Arg>,
    kinds: List<ArgKind?>,
    mapped: Boolean,
    prefixes: List<String>?,
    formatArg: Arg?,
    src: String,
    opts: Reading,
    hyphenated: Set<String>,
    issues: ArrayList<Issue>,
    spans: List<IntRange>? = null,
): List<Issue> {
    val block = formatArg == null
    val view = markupView(src, format)
    val starts = format.interpolations.map { it.decodedStart } + at
    // interpolate reads the markup with each Trusted value's text in place: known where the value is a literal.
    val written = HashMap<Int, String>()
    val unknown = ArrayList<Int>()
    if (mapped) {
        for ((k, a) in values.withIndex()) {
            if (kinds[k] != ArgKind.TRUSTED) continue
            val text = trustedLiteral(src, a.text, s.start)
            if (text == null) unknown += at[k] else written[format.interpolations.size + k] = text
        }
    }
    val reading =
        readValues(
            view,
            starts.map { it until it + PLACEHOLDER.length },
            prefixes ?: listOf(opts.prefix, CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).distinct(),
            written,
        )
    val landings = reading.landings
    // After a Trusted whose text is not known, a value may land elsewhere than the markup shows.
    val uncertainFrom = unknown.minOrNull() ?: Int.MAX_VALUE
    val percentAt = { j: Int -> j >= format.interpolations.size }
    // A fix that writes the literal anew writes every template of it, the marked ones too.
    val trapOf = traps(if (block) s else format, src)
    val attributeValues = ExpressionValues(view)
    for ((k, ip) in format.interpolations.withIndex()) {
        val landing = landings[k]
        val told = factOf(format, src, opts, ip)
        val fact = told.plain()
        if (ip.kind == InterpolationKind.SIMPLE && landing.position == Position.DATASTAR) {
            val judged = trapOf(ip)?.let { datastarTrap(it, ip, landing, attributeValues, opts.prefix, fact) }
            if (judged != null) {
                issues += judged
                continue
            }
        }
        // Kotlin fills in the digits of an unsigned number before interpolate sees the markup, so it is a number here.
        if (fact.writesItself()) continue
        if (fact.isNullable() && landing.position in ESCAPED_POSITIONS) continue
        // Markup in element text is safe as markup, but interpolate reads it as format: a %s in it is a placeholder.
        val markupText = fact.isMarkup() && landing.markupInPlace
        // In a block the markup is read with the marks alone taken out, so markup written into it is markup as written.
        if (block && markupText) continue
        val shown = valueShown(ip)
        // A `%` right before the template would make `%%s` of the placeholder: a per cent sign and an s.
        val percents = if (block) 0 else runBefore(format.text, ip.decodedStart, '%')
        // A %s placeholder writes nothing for null, which a template writes as null: the fix is offered, and not put first.
        val nullable = told.isNullableString() || mayBeNull(src, ip.text, s.start)
        // It escapes markup held as a String, so the fix would change the page; an Html or a Trusted it writes as it is.
        val keeps =
            if (markupText) {
                markupValued(src, format, ip)
            } else {
                !(landing.position == Position.TEXT && mayHoldMarkup(src, ip.text, s.start, opts.constants))
            }
        // As a %s placeholder it would be refused, along with every value after it in the URL, unless the literal before it fixes the
        // host, and after a scheme the markup writes that does more than navigate.
        val loadsAnywhere = hostUnfixed(reading, starts, k) { j -> j == k || percentAt(j) }
        val fixable =
            landing.position in ESCAPED_POSITIONS && !loadsAnywhere && mapped &&
                (ip.kind != InterpolationKind.OPERAND || (block && !ip.unread)) &&
                percents % 2 == 0 && keeps && runningScheme(reading, starts, k) == null
        // Under trimIndent the trim now runs before the value goes in, so a line break in it is no longer trimmed.
        val indentTrimmed = trimAfter(src, format)?.let { it.margin == null } == true
        val asIs = markupText || told == ValueFact.TRUSTED || (!told.isOther() && trustedValue(src, ip.text, s.start))
        val fixes =
            when {
                !fixable -> {
                    emptyList()
                }

                formatArg == null -> {
                    // After a + written without a space, a mark's own + would make ++ of the two.
                    val tight = ip.start > 0 && src[ip.start - 1] == '+'
                    listOf(markFix(format, ip, shown, asIs, !nullable && !indentTrimmed, first = ip.start == s.start, tight))
                }

                else -> {
                    listOf(placeholderFix(ip, shown, at, values, formatArg, asIs, !nullable && !indentTrimmed))
                }
            }
        val base = "Kotlin fills in $shown before interpolate sees the markup, so interpolate neither escapes nor refuses it."
        // What to do about it: the value handed to interpolate, as each form hands one over.
        val handOver = if (block) "mark it with +" else "make it a %s placeholder"
        val message =
            when {
                loadsAnywhere -> {
                    "$base It lands in ${landing.attribute ?: "the URL"}, which loads code from wherever the value points, and the " +
                        "markup before it does not fix the host. $CODE_URL_ADVICE, and $handOver."
                }

                // Markup a Kotlin template writes into the format, which interpolate then reads as format.
                markupText -> {
                    "Kotlin fills in $shown before interpolate reads the markup, so interpolate reads what it holds as markup too: " +
                        "a %s in it is a placeholder and a %% a per cent sign, and interpolate throws or changes the markup. " +
                        "Make it a %s placeholder and pass the Html: interpolate writes an Html or a Trusted there as it is."
                }

                landing.position in ESCAPED_POSITIONS -> {
                    if (block) {
                        "$base Mark it with +, and interpolate escapes it."
                    } else {
                        "$base Make it a %s placeholder and pass the value to interpolate."
                    }
                }

                else -> {
                    val (where, instead) =
                        landingWords(
                            landing,
                            endTagAt(view, ip.decodedStart),
                            rawElement(view, landing, ip.decodedStart),
                        )
                    "$base It lands in $where. $instead"
                }
            } + if (fixable && nullable) " Where $shown is null, interpolate writes nothing, and Kotlin writes null." else ""
        issues += Issue(ip.start, ip.end, message, Severity.WARNING, "interpolate-template", valueLink(landing), fixes)
    }
    for ((k, d) in at.withIndex()) {
        val landing = landings[format.interpolations.size + k]
        val arg = if (mapped) values[k] else null
        val kind = if (arg != null) kinds[k] else null
        // In a block the value's own template or operand is marked; a %s is where it stands in the format.
        val r = spans?.get(k) ?: format.toSource(d, d + PLACEHOLDER.length)
        val base = format.interpolations.size
        val uncertain = d > uncertainFrom
        val shown = arg?.let { shortened(it.text) } ?: "the value of this %s"

        // An error only where interpolate throws for certain.
        fun refused(
            message: String,
            certain: Boolean,
            fixes: List<Fix> = emptyList(),
        ): Issue {
            val severity = if (certain) Severity.ERROR else Severity.WARNING
            return Issue(r.first, r.last + 1, message, severity, "unsafe-interpolation", valueLink(landing), fixes)
        }
        // interpolate refuses everything but Trusted in a URL that loads code, unless what comes before the
        // URL's first value nobody vouched for fixes its host: the markup as written, a literal Trusted's text
        // in place. It names that value, and a URL of Trusted values alone goes unjudged.
        val attribute = landing.attribute
        if (landing.loadsCode && attribute != null) {
            // A Trusted of known text is read as markup and is not among them.
            val inUrl = at.indices.filter { landings[base + it].valueStart == landing.valueStart }
            val first = inUrl.firstOrNull { (if (mapped) kinds[it] else null) != ArgKind.TRUSTED }
            // A Trusted before it whose text is not known ends what is known to come before.
            val vouched = if (first != null && inUrl[0] != first) inUrl[0] else null
            val before = first?.let { reading.prefix(base + k, base + (vouched ?: it)) }
            if (first != null && before != null && !urlHostFixedBy(attribute, before)) {
                if (first != k) continue
                // A template before the placeholder is filled in first, so what the literal fixes is known only without one.
                val templated = format.interpolations.any { it.decodedStart >= landing.valueStart && it.decodedStart < d }
                val certain = !templated && vouched == null && !uncertain && kind != null
                val where = "$attribute, which loads code from wherever the value points"
                val instead = "$CODE_URL_ADVICE, or wrap a URL you chose yourself in Trusted(...)."
                val message =
                    when {
                        vouched != null || uncertain -> {
                            "interpolate throws here unless the Trusted text before $shown fixes the host or $shown is " +
                                "Trusted(...): it lands in $where. $instead"
                        }

                        certain -> {
                            "interpolate always throws here: $shown lands in $where, and the markup before it does not fix the host. " +
                                "Only Trusted(...) is let in there. $instead"
                        }

                        else -> {
                            "interpolate throws here unless $shown is Trusted(...): it lands in $where, " +
                                "and the markup before it does not fix the host. $instead"
                        }
                    }
                issues += refused(message, certain)
                continue
            }
        }
        // After a scheme the markup writes that does more than navigate, interpolate refuses the URL whatever its values.
        val scheme = if (kind == ArgKind.TRUSTED) null else runningScheme(reading, starts, base + k)
        if (scheme != null && attribute != null) {
            val templated = format.interpolations.any { it.decodedStart >= landing.valueStart && it.decodedStart < d }
            val certain = !templated && !uncertain
            val message =
                "interpolate throws here ${if (certain) "for every value" else "unless the text before it moves it"}: " +
                    "the markup starts $attribute with $scheme:, which is not a scheme that only navigates. " +
                    "Start the URL with /, # or https://."
            issues += refused(message, certain)
            continue
        }
        if (landing.position in ESCAPED_POSITIONS) {
            // Markup held as a String is text to interpolate: in element text it escapes the tags, which are then printed.
            if (landing.position == Position.TEXT && arg != null && kind != ArgKind.MARKUP && kind != ArgKind.TRUSTED &&
                markupHeldAsString(src, arg.text, s.start)
            ) {
                val message =
                    "interpolate escapes ${shortened(arg.text)} here, so the tags it holds are printed as text: it is markup held " +
                        "as a String. Pass the Html itself, join a list of Html with joinToHtml(), or wrap markup you built " +
                        "yourself in Trusted(...)."
                issues += Issue(r.first, r.last + 1, message, Severity.WARNING, "interpolate-template", valueLink(landing))
            }
            continue
        }
        val allowed =
            when (landing.position) {
                Position.DATASTAR -> setOf(ArgKind.NUMBER, ArgKind.BOOLEAN)
                Position.STYLE -> setOf(ArgKind.NUMBER)
                else -> emptySet<ArgKind>()
            }
        if (kind == ArgKind.TRUSTED || kind in allowed) continue
        val unsigned =
            kind == ArgKind.UNSIGNED && arg != null && (landing.position == Position.DATASTAR || landing.position == Position.STYLE)
        val (where, instead) = landingWords(landing, endTagAt(view, d), rawElement(view, landing, d))
        val letIn = letIn(landing.position)
        val message =
            when {
                uncertain && kind != null -> {
                    "interpolate throws here unless the Trusted text before $shown moves it: it lands in $where, " +
                        "where only $letIn is let in. $instead"
                }

                unsigned -> {
                    "interpolate always throws here: $shown is an unsigned number, which is not a kotlin.Number, and it lands in " +
                        "$where, where only $letIn is let in. Convert it with toLong() first."
                }

                kind != null -> {
                    "interpolate always throws here: $shown lands in $where, and only $letIn is let in there. $instead"
                }

                else -> {
                    "interpolate throws here unless $shown is $letIn: it lands in $where. $instead"
                }
            }
        issues += refused(message, kind != null && !uncertain, if (unsigned) listOf(toLongFix(arg!!)) else emptyList())
    }
    issues += mapIssues(format, src, analyzeHtml(view, opts, hyphenated, templates = false))
    return issues
}

private val IF_START = regex("""^if\s*\(""")

/**
 * The kind of a value handed to `interpolate`: a literal, `Trusted(...)`, a call to
 * `interpolate` (its Html), or a name the enclosing declaration gives a type. Null when the text
 * does not show it. Not [valueKind] in ValueKinds.kt, which asks whose text a value is: this asks
 * the type interpolate's `admits` sees, so `1u` is UNSIGNED here and not a literal there.
 */
private fun argKind(
    src: String,
    text: String,
    literal: Boolean,
    at: Int,
): ArgKind? {
    val t = text.trim()
    if (literal) return ArgKind.STRING
    if (KOTLIN_NUMBER.matchEntire(t) != null) return if (UNSIGNED_SUFFIX.containsMatchIn(t)) ArgKind.UNSIGNED else ArgKind.NUMBER
    if (t == "true" || t == "false") return ArgKind.BOOLEAN
    if (t == "null") return ArgKind.NULL
    if (CHAR_LITERAL.matchEntire(t) != null) return ArgKind.CHAR
    if (IF_START.containsMatchIn(t) && literalOnly(t)) return if ('"' in t) ArgKind.STRING else null
    if (coreCall(src, t, "Trusted") || (KOTLIN_NAME.matchEntire(t) != null && trustedName(src, t, at))) return ArgKind.TRUSTED
    if (coreCall(src, t, "interpolate")) return ArgKind.MARKUP
    // The String of the Html: interpolate(...).text.
    val html = TEXT_OF_VALUE.find(t)?.groupValues?.get(1)
    if (html != null && coreCall(src, html, "interpolate")) return ArgKind.STRING
    callKind(src, t, at, emptySet())?.argKind()?.let { return it }
    if (KOTLIN_NAME.matchEntire(t) == null) return null
    // `val name = "Gorvek"`: a String, though no type is written.
    val declared = declaredKind(src, t, at) ?: return if (declarationOf(src, t, at)?.string == true) ArgKind.STRING else null
    return declared.argKind()
}

/** The value converted to a Long, which is a `kotlin.Number`: `u.toLong()`, `(a + b).toLong()`. */
private fun Analyzer.toLongFix(arg: Arg): Fix {
    val t = arg.text.trim()
    val receiver = if (SIMPLE_RECEIVER.matchEntire(t) != null) t else "($t)"
    return Fix("Convert ${shortened(t)} with toLong()", arg.start, arg.end, "$receiver.toLong()")
}

/** How many times [c] stands right before [at] in [text]. */
private fun Analyzer.runBefore(
    text: String,
    at: Int,
    c: Char,
): Int {
    var k = at
    while (k > 0 && text[k - 1] == c) k--
    return at - k
}

/** Whether [at] in the markup is past the name of an end tag, `</p x="%s">`, whose attributes the browser drops. */
private fun Analyzer.endTagAt(
    view: String,
    at: Int,
): Boolean {
    val lt = if (at > 0) view.lastIndexOf('<', at - 1) else -1
    return lt >= 0 && END_TAG_ATTRIBUTES.matchEntire(view.substring(lt, at)) != null
}

/**
 * The fix that marks the value of [ip] in a block: `$name` and `${name}` become `${+name}`, with
 * the dollars the literal opens a template with, and a value joined with `+` becomes `+name`.
 */
private fun Analyzer.markFix(
    format: KotlinString,
    ip: Interpolation,
    shown: String,
    asIs: Boolean,
    preferred: Boolean,
    first: Boolean,
    tight: Boolean,
): Fix {
    val expr = if (ip.kind == InterpolationKind.SIMPLE) ip.text else ip.text.trim()
    val operand = markOperand(expr)
    val title = "Mark $shown with +, " + if (asIs) "where interpolate writes it as it is" else "which interpolate escapes"
    // The dollars of the literal the template stands in, which in a chain is one of several.
    val dollars = (format.literals.firstOrNull { ip.start in it.start until it.end } ?: format).dollars
    val marked =
        when {
            ip.kind != InterpolationKind.OPERAND -> "$".repeat(dollars) + "{+" + operand + "}"

            // A mark that opens the chain has no string before it to be joined to.
            first -> "\"\${+$operand}\""

            tight -> " +$operand"

            else -> "+$operand"
        }
    return Fix(title, ip.start, ip.end, marked, preferred = preferred)
}

/**
 * The template made a `%s` placeholder in place, and its expression passed as the value at the same
 * place among the others; [asIs] where interpolate writes the value as it is, [preferred] where
 * the page is left as it was.
 */
private fun Analyzer.placeholderFix(
    ip: Interpolation,
    shown: String,
    placeholders: List<Int>,
    values: List<Arg>,
    format: Arg,
    asIs: Boolean,
    preferred: Boolean,
): Fix {
    val index = countBelow(placeholders, ip.decodedStart)
    val expr = if (ip.kind == InterpolationKind.SIMPLE) ip.text else ip.text.trim()
    val before = values.getOrNull(index)
    val after = values.lastOrNull() ?: format
    val edit = if (before != null) Edit(before.start, before.start, "$expr, ") else Edit(after.end, after.end, ", $expr")
    val title = "Make $shown a %s placeholder, " + if (asIs) "where interpolate writes it as it is" else "which interpolate escapes"
    return Fix(title, ip.start, ip.end, "%s", listOf(edit), preferred)
}

/** The page an issue of a value links to: Datastar's guide where Datastar runs it, the strings page anywhere else. */
private fun valueLink(landing: ValueLanding): String = if (landing.position == Position.DATASTAR) Docs.EXPRESSIONS else Docs.STRINGS

/**
 * What to write instead of a value in a Datastar attribute, ended by [end]: one in Pro's media query
 * is the query, which belongs in the markup or in the DSL's call; any other is read from an attribute of its own.
 */
private fun Analyzer.datastarAdvice(
    attribute: String?,
    end: String,
): String =
    if (catalog.parseAttributeName(attribute.orEmpty().asciiLowercase(), CatalogNames.PREFIX)?.spec?.name == "match-media" ||
        attribute?.substringBefore(':')?.endsWith("match-media") == true
    ) {
        "Write the query into the markup, where a constant of yours may stand as a Kotlin template, or set it with the DSL's dataMatchMedia$end"
    } else {
        "Put the value in an attribute of its own, such as data-label, and read it in the expression with el.dataset.label$end"
    }

/** Where a placeholder that is not escaped lands, said for a message, and what to write instead. */
private fun Analyzer.landingWords(
    landing: ValueLanding,
    endTag: Boolean,
    element: String? = null,
): Pair<String, String> =
    when (landing.position) {
        Position.DATASTAR -> {
            "${landing.attribute ?: "a Datastar attribute"}, which Datastar runs as an expression or follows as a signal path" to
                datastarAdvice(landing.attribute, ".")
        }

        Position.STYLE -> {
            "${landing.attribute ?: "style"}, which is read as CSS" to
                "Pass a number and write the unit in the markup, or choose between classes."
        }

        Position.CODE if landing.attribute == null && element == "style" -> {
            "a <style>, where it is read as CSS" to
                "Choose between classes, or write a number; wrap CSS you wrote yourself in Trusted(...)."
        }

        Position.CODE -> {
            "${landing.attribute ?: "a script"}, which the browser runs" to
                "Put the value in a data-* attribute and read it from there; wrap markup you built yourself in Trusted(...)."
        }

        else -> {
            if (endTag) {
                "an end tag, which takes no attributes" to "Write the end tag from literals alone."
            } else {
                "the markup's own structure, a tag name, an attribute name or an unquoted value" to
                    "Build that part of the markup from literals, or quote the attribute value; wrap markup you built yourself in Trusted(...)."
            }
        }
    }

/** What interpolate lets in where escaping is not enough. */
private fun Analyzer.letIn(position: Position): String =
    when (position) {
        Position.DATASTAR -> "a number or a boolean"
        Position.STYLE -> "a number"
        else -> "Trusted(...)"
    }

/**
 * Whether the placeholder at [k] is in a URL that loads code, `src` of a script and the like, whose host
 * the literal before the URL's first placeholder does not fix: interpolate refuses every value there but
 * Trusted, as Landscape.checkUrls in core does. [counts] says which placeholders interpolate sees as placeholders.
 */
private fun Analyzer.hostUnfixed(
    reading: ValueReading,
    starts: List<Int>,
    k: Int,
    counts: (Int) -> Boolean,
): Boolean {
    val landing = reading.landings[k]
    val attribute = landing.attribute
    if (!landing.loadsCode || attribute == null) return false
    var first = k
    for ((j, l) in reading.landings.withIndex()) {
        if (counts(j) && l.valueStart == landing.valueStart && starts[j] < starts[first]) first = j
    }
    return !urlHostFixedBy(attribute, reading.prefix(k, first))
}

/**
 * The placeholders of [view] as interpolate reads them: valueLandings with the text of the [known] placeholders,
 * a literal Trusted's, in place. A known text can hold the start of a value, where valueLandings
 * gives the offset of the placeholder, so a prefix is read from the markup as written.
 */
private fun Analyzer.readValues(
    view: String,
    placeholders: List<IntRange>,
    prefixes: List<String>,
    known: Map<Int, String>,
): ValueReading {
    val landings = valueLandings(view, placeholders, prefixes, known)
    if (known.isEmpty()) return ValueReading(landings) { j, to -> view.substring(landings[j].valueStart, placeholders[to].first) }
    val text = StringBuilder()
    var cut = 0
    val starts = IntArray(placeholders.size)
    for (i in placeholders.indices.sortedBy { placeholders[it].first }) {
        val placeholder = placeholders[i]
        text.append(view, cut, placeholder.first)
        starts[i] = text.length
        text.append(known[i] ?: view.substring(placeholder.first, placeholder.last + 1))
        cut = placeholder.last + 1
    }
    text.append(view, cut, view.length)
    val markup = text.toString()
    val open = placeholders.indices.filter { it !in known }
    val written =
        lazy(LazyThreadSafetyMode.NONE) {
            valueLandings(markup, open.map { starts[it] until starts[it] + placeholders[it].last + 1 - placeholders[it].first }, prefixes)
        }
    return ValueReading(landings) { j, to -> markup.substring(written.value[open.indexOf(j)].valueStart, starts[to]) }
}

/**
 * The scheme the markup writes before the value at [k] of a URL that does more than navigate,
 * `javascript`, after which interpolate refuses every value; null where there is none. Only the
 * literal before the URL's first value counts, as with a host it fixes.
 */
private fun Analyzer.runningScheme(
    reading: ValueReading,
    starts: List<Int>,
    k: Int,
): String? {
    val landing = reading.landings[k]
    val attribute = landing.attribute ?: return null
    if (landing.position != Position.URL) return null
    var first = k
    for ((j, l) in reading.landings.withIndex()) if (l.valueStart == landing.valueStart && starts[j] < starts[first]) first = j
    return refusedUrlScheme(attribute, reading.prefix(k, first))
}

/**
 * The whole literal rewritten as a `$$` literal (Kotlin 2.2+): the flagged template becomes a
 * signal by staying as it is, every other template gains a dollar so it stays Kotlin, and the
 * `${'$'}` idiom becomes the plain dollar it always meant. [dollars] is how many the literal
 * needs; the text is made only when it is read.
 */
private fun Analyzer.multiDollarFix(
    s: KotlinString,
    src: String,
    signal: Interpolation,
    dollars: Int,
): Fix {
    val prefix = "$".repeat(dollars)
    return Fix.later("Make it a $prefix literal, where \$${signal.text} is a signal", s.start, s.end) {
        // A kept template gains the dollars it now needs; the dollars before it stay text, as they were.
        // The flagged one keeps its run, which is now text.
        val keep = s.interpolations.filter { it !== signal }.mapTo(HashSet()) { it.start }
        val out = StringBuilder(prefix).append(src.substring(s.start, s.contentStart).trimStart('$'))
        var i = s.contentStart
        while (i < s.contentEnd) {
            if (i in keep) out.append("$".repeat(dollars - 1))
            if (src.startsWith("\${'$'}", i)) {
                out.append('$')
                i += 6
                continue
            }
            out.append(src[i])
            i++
        }
        out.append(src, s.contentEnd, s.end).toString()
    }
}

/**
 * The trap of each `$name` in a literal, `kotlin-interpolation`, made when a placeholder asks for it:
 * what every placeholder of the literal shares is read once, when the first asks. [available] names the DSL helpers
 * a fix may write, each with its import; null where the literal is handed to a DSL call.
 */
internal fun Analyzer.traps(
    s: KotlinString,
    src: String,
    available: Set<String>? = null,
): (Interpolation) -> Issue? {
    // Literals joined with `+` are one string to the judgement, but a prefix makes only the first a `$$` literal.
    val whole = s.literals.isEmpty()
    val content by lazy(LazyThreadSafetyMode.NONE) { src.substring(s.contentStart, s.contentEnd) }
    // Enough dollars that no run already in the text opens a template: one more than the longest run.
    val dollars by lazy(LazyThreadSafetyMode.NONE) { maxOf(2, (DOLLAR_RUNS.findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1) }
    // The literal as the author meant it, with every template read as a signal.
    val helpers by lazy(LazyThreadSafetyMode.NONE) {
        val found = ArrayList<Fix>()
        if (whole && !BRACED_TEMPLATE.containsMatchIn(content)) {
            // A helper is not a string: it replaces the literal together with the trim call after it.
            val end = s.end + (trimAfter(src, s)?.text?.length ?: 0)
            for ((re, helper) in HELPERS) {
                if (available != null && helper !in available) continue
                // The whole content: `$` alone would also match before a final line break.
                val m = re.matchEntire(content) ?: continue
                val call = "$helper(\"${m.groupValues[1]}\")"
                found.add(0, Fix("Use $call", s.start, end, call, importEdits(src, HTML_PACKAGE, helper)))
            }
        }
        found
    }
    // Where the DSL may be missing, signal(...) is advised only where it can be called.
    val advisesSignal = available == null || "signal" in available
    return trap@{ ip ->
        // In a multi-dollar literal a single $ is text, so there is no trap: every template there is deliberate.
        if (ip.kind != InterpolationKind.SIMPLE || ip.dollars > 1) return@trap null
        val fixes = ArrayList<Fix>(helpers)
        if (whole) fixes += multiDollarFix(s, src, ip, dollars)
        fixes += Fix("Escape as \${'$'}${ip.text}", ip.start, ip.start + 1, "\${'$'}")
        if (!s.rawAt(ip)) fixes += Fix("Escape as \\$${ip.text}", ip.start, ip.start, "\\")
        Issue(
            start = ip.start,
            end = ip.end,
            message =
                "Kotlin interpolates \$${ip.text} here; the browser will never see a signal. " +
                    (if (advisesSignal) "Write signal(\"${ip.text}\"), make" else "Make") +
                    " the literal \$\$\"...\" (Kotlin 2.2+), or escape as \${'$'}${ip.text}.",
            severity = Severity.ERROR,
            code = "kotlin-interpolation",
            link = Docs.EXPRESSIONS,
            fixes = fixes,
        )
    }
}

/**
 * Each placeholder of an HTML string, judged by where it lands as `interpolate` judges it at run time
 * (core's [valueLandings]): escaping saves it, or nothing does. What the analysis can see is the
 * author's own (a constant, a literal, markup built here) is left alone; what it cannot see
 * into is never an error, since a build fails on errors and the text cannot tell `count: Int`
 * from a String.
 */
internal fun Analyzer.judgeValues(
    s: KotlinString,
    src: String,
    opts: Reading,
    view: String,
    inner: Boolean = false,
): Judged {
    if (s.interpolations.isEmpty()) return Judged()
    val trapOf = traps(s, src)
    // Without core there is no interpolate to build the string with, and Trusted is not Streamlord's.
    val sdk = opts.modules
    val core = sdk.core
    // A fix writes interpolate or Trusted only where the name is core's: not somebody else's call or import.
    val canInterpolate = coreNameUsable(src, "interpolate", sdk)
    val canTrust = coreNameUsable(src, "Trusted", sdk)
    // A literal Trusted is markup the author wrote, read in place: the other placeholders are judged against it.
    val written = HashMap<Int, String>()
    for ((k, ip) in s.interpolations.withIndex()) {
        // A Trusted the editor resolves to another package is none of core's, whatever the text says.
        if (ip.kind == InterpolationKind.OPERAND || !core || opts.valueFact?.invoke(ip.start, ip.end).isOther()) continue
        trustedLiteral(src, ip.text, s.start)?.let { written[k] = it }
    }
    val reading =
        readValues(
            view,
            s.interpolations.map { it.decodedStart until it.decodedStart + PLACEHOLDER.length },
            // Both bundles' prefixes, as interpolate reads them: a value is judged by what either would run.
            listOf(opts.prefix, CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).distinct(),
            written,
        )
    val landings = reading.landings
    val told = s.interpolations.map { factOf(s, src, opts, it) }
    val starts = s.interpolations.map { it.decodedStart }
    val placeholders = s.interpolations.indices
    // What the rewrite can do is asked only by a value that has something to say of it: read when the first asks.
    // interpolate takes a Trusted wherever it lands and writes it as it is, as a template does.
    val trusted by lazy(LazyThreadSafetyMode.NONE) {
        placeholders.map { k ->
            told[k] == ValueFact.TRUSTED ||
                (!told[k].isOther() && trustedValue(src, s.interpolations[k].text, s.start))
        }
    }
    // It refuses every other value in a URL that loads code unless the literal fixes its host, and every value
    // after a scheme the literal writes that does more than navigate.
    val loose by lazy(LazyThreadSafetyMode.NONE) {
        placeholders.map { k -> hostUnfixed(reading, starts, k) { true } || runningScheme(reading, starts, k) != null }
    }
    val keeps by lazy(LazyThreadSafetyMode.NONE) {
        placeholders.map { k ->
            trusted[k] || (!loose[k] && interpolateKeeps(src, s, s.interpolations[k], told[k], landings[k].position, opts))
        }
    }
    // CSS in the body of a <style> is the author's to vouch for: the rewrite passes it as Trusted(...), which takes
    // a String, and any other value as the text a template writes of it, which a class of the author's may make CSS:
    // by whether it is shown to be a String. A collection it does not vouch for, which a template writes in brackets.
    val vouched by lazy(LazyThreadSafetyMode.NONE) {
        placeholders
            .filter { k ->
                val text = s.interpolations[k].text
                canTrust && !keeps[k] && (told[k].isString() || told[k] == null) &&
                    rawElement(view, landings[k], starts[k]) == "style" && !mayBeNull(src, text, s.start) &&
                    !collectionShown(src, text, s.start)
            }.associateWithTo(LinkedHashMap()) { k ->
                told[k].isString() || shownType(src, s.interpolations[k].text, s.start)?.let { ValueFact.ofTypeName(it) }.isString()
            }
    }
    // A value interpolate would not take as it is, though it is safe where it lands, stays unmarked, and Kotlin fills
    // it in as before: one of the author's own, a constant or `${"$"}`, and digits, true, false or null in a script or a style.
    val kept by lazy(LazyThreadSafetyMode.NONE) {
        placeholders.filterTo(HashSet()) { k ->
            val ip = s.interpolations[k]
            val own = told[k].isConstant()
            val scalar = told[k].scalar() && verdict(told[k], landings[k].position) == Verdict.SAFE
            !keeps[k] && ip.kind != InterpolationKind.OPERAND && (own || scalar)
        }
    }
    val trim = trimAfter(src, s)
    // Why the string cannot be rewritten whatever its values are, in a few words, or null where it can.
    val unfit by lazy(LazyThreadSafetyMode.NONE) {
        when {
            s.called -> {
                "a call after one literal of the + chain applies to that literal alone"
            }

            s.unread -> {
                "what is joined to the string with + cannot be read as values"
            }

            // The trim call stays on the string, in the block: one that could be read here.
            trim != null && trim.text == null -> {
                "the trim call after the string cannot be kept"
            }

            // In the block a `this` without a label is the block's own, and no longer what the string wrote.
            s.interpolations.any { OWN_THIS.containsMatchIn(it.text.replace(NO_CODE, " ")) } -> {
                "in the block of interpolate, this and a call without a receiver such as toString() are the block's"
            }

            // An annotation and a const val take a constant, which no call is.
            constantOnly(src, s.start) -> {
                ""
            }

            else -> {
                null
            }
        }
    }
    // interpolate(...) can take the string only when every value is one it accepts where it lands.
    val fixable by lazy(LazyThreadSafetyMode.NONE) {
        canInterpolate && unfit == null && placeholders.all { keeps[it] || it in vouched.keys || it in kept }
    }
    // The first values it does not take as they are: what the message of another value names.
    val inTheWay by lazy(LazyThreadSafetyMode.NONE) { placeholders.filterNot { keeps[it] || it in kept }.take(2) }
    // Whether the rewrite leaves the page as it was, so that an editor may prefer it: not where it vouches for a
    // value, where one may be null, which a template writes as `null` and interpolate as nothing, nor under
    // trimIndent, where a value with a line break changed what was trimmed.
    val nullable by lazy(LazyThreadSafetyMode.NONE) {
        placeholders.firstOrNull {
            told[it].isNullable() || told[it].isNullableString() ||
                mayBeNull(src, s.interpolations[it].text, s.start)
        }
    }
    val same by lazy(LazyThreadSafetyMode.NONE) { vouched.isEmpty() && (trim == null || trim.margin != null) && nullable == null }
    // The rewrite is made once for the string, and only where an issue offers it.
    val rewrite by lazy(LazyThreadSafetyMode.NONE) { interpolateFix(src, s, trim?.text.orEmpty(), vouched, kept) }
    var offered = false
    // Whether a value that starts a URL asks for it, which the title then names: interpolate refuses one that runs.
    var urlAsked = false

    fun rewriteAs(preferred: Boolean = false): Fix {
        offered = true
        return rewrite.copy(preferred = preferred && same)
    }
    val issues = ArrayList<Issue>()
    val attributeValues = ExpressionValues(view)
    val operands = s.interpolations.any { it.kind == InterpolationKind.OPERAND }
    for ((k, ip) in s.interpolations.withIndex()) {
        if (k in written) continue
        val landing = landings[k]
        val fact = told[k].plain()
        val shown = valueShown(ip)
        val factSays = verdict(told[k], landing.position)
        // In a Datastar expression `$name` may be meant as a signal, whatever the value is.
        if (factSays == Verdict.SAFE && landing.position != Position.DATASTAR) continue
        val severity = if (fact.knownCharacters()) Severity.WARNING else Severity.HINT

        // The rewrite escapes nothing of a Trusted, so the issue of one does not offer it.
        fun rewritten(preferred: Boolean = true): List<Fix> = if (fixable && !trusted[k]) listOf(rewriteAs(preferred)) else emptyList()

        // What the rewrite does with another value, or why none is offered: the first value in the way.
        fun rewriteNote(): String {
            if (!canInterpolate || trusted[k]) return ""
            if (!unfit.isNullOrEmpty()) return " Marking the values is not offered here: $unfit."
            // A value that may be null, which a template writes as null and the rewrite as nothing.
            val nothing =
                nullable?.takeIf { fixable }?.let {
                    " interpolate writes nothing for ${valueShown(
                        s.interpolations[it],
                    )} where it is null, and Kotlin writes null."
                } ?: ""
            // This value alone in the way, for the reason interpolateKeeps gives in element text.
            val own = !keeps[k] && landing.position == Position.TEXT && mayHoldMarkup(src, ip.text, s.start, opts.constants)
            val other = inTheWay.firstOrNull { it != k } ?: return (if (own) OWN_MARKUP_NOTE else "") + nothing
            val named = valueShown(s.interpolations[other])
            val place = refusedPlace(view, landings[other], starts[other])
            val refused = loose[other] || landings[other].position !in ESCAPED_POSITIONS
            return when {
                fixable -> " The fix marks $named, in $place, as Trusted(...).$nothing"
                refused -> " interpolate cannot take this string while $named stands in $place."
                else -> " Marking the values is not offered here: it would escape $named, which may be markup you built."
            }
        }

        fun issue(
            message: String,
            severity: Severity = Severity.WARNING,
            code: String = "unsafe-interpolation",
            fixes: List<Fix> = emptyList(),
        ): Issue = Issue(ip.start, ip.end, message, severity, code, valueLink(landing), fixes)

        fun unescaped(): Issue {
            // A String in element text may be markup the author built, which only the author knows: then
            // both the rewrite, which escapes it, and Trusted, which keeps it, are offered, and neither first.
            val text = landing.position == Position.TEXT
            val trust =
                text && !told[k].isNullableString() &&
                    (
                        fact.isString() ||
                            (
                                fact == null &&
                                    (callResult(ip.text) || markupTextValue(src, ip.text, s.start)) &&
                                    mayHoldMarkup(src, ip.text, s.start, opts.constants)
                            )
                    ) &&
                    !mayBeNull(src, ip.text, s.start) && !collectionShown(src, ip.text, s.start)
            // A Trusted or an Html that opens a `+` chain would be joined to nothing, since Kotlin has no plus for
            // either: no such fix there, nor such advice.
            val opens = ip.start == s.start
            val fixes =
                rewritten(!trust) +
                    if (trust && canTrust && !opens && !ip.unread) listOf(trustedFix(src, ip, shown)) else emptyList()
            // Without core, plain Kotlin and kotlinx.html stand in for interpolate and Trusted, and a call or a
            // value of unknown type may be markup the author composed; a known String stays a warning.
            val message = unescapedMessage(shown, fact.isMarkup(), text, canInterpolate, canTrust && !opens, opts.modules.coreHtml)
            val level = if (!core && trust && fact == null) Severity.HINT else severity
            return issue(message + rewriteNote(), level, "unescaped-interpolation", fixes)
        }

        fun schemeIssue(attribute: String): Issue {
            val fixes = rewritten()
            urlAsked = urlAsked || fixes.isNotEmpty()
            return issue(
                "Kotlin writes $shown at the start of $attribute, so the value decides the scheme, " +
                    "and a javascript: URL runs. Escaping does not help: start the value with /, # or https://" +
                    (if (fixes.isNotEmpty()) ", or mark the value with + in $REFUSES_SCHEME." else ".") + rewriteNote(),
                fixes = fixes,
            )
        }

        // Its digits are safe; only interpolate would refuse it, which a project without core has none of.
        fun unsigned(where: String): Issue? =
            if (!core) {
                null
            } else {
                issue(
                    "Kotlin writes $shown into $where as digits, which is safe there, but it is an unsigned number, which " +
                        "interpolate refuses there: it is not a kotlin.Number. Convert it with toLong() if you build this with interpolate.",
                    Severity.HINT,
                )
            }

        issues +=
            when (landing.position) {
                Position.TEXT, Position.ATTRIBUTE -> {
                    // The content of a <meta> may be a refresh, whose URL interpolate judges as any URL.
                    when {
                        factSays != Verdict.ESCAPE -> null
                        refreshUrlStart(view, landing) { reading.prefix(k, k) } -> schemeIssue("the URL of content")
                        else -> unescaped()
                    }
                }

                Position.URL -> {
                    val attribute = landing.attribute ?: "the URL"
                    val prefix = reading.prefix(k, k)
                    val scheme = runningScheme(reading, starts, k)
                    when {
                        scheme != null -> {
                            issue(
                                "The markup starts $attribute with $scheme:, which is not a scheme that only navigates: the URL can " +
                                    "run whatever $shown holds, and escaping does not help, nor would interpolate, which refuses " +
                                    "every value there. Start the URL with /, # or https://." + rewriteNote(),
                            )
                        }

                        landing.loadsCode && !urlHostFixedBy(attribute, prefix) -> {
                            issue(
                                "Kotlin writes $shown into $attribute, which loads code from wherever the value points, and the " +
                                    "markup before it does not fix the host: even an https:// URL can load code from another " +
                                    "origin. Escaping does not help. $CODE_URL_ADVICE.",
                            )
                        }

                        urlSchemeFixedBy(attribute, decodeEntities(prefix).text) -> {
                            unescaped()
                        }

                        else -> {
                            schemeIssue(attribute)
                        }
                    }
                }

                Position.STYLE -> {
                    val where = landing.attribute ?: "style"
                    val advice =
                        if (fact.isNullable()) {
                            "It is a number that may be null, which Kotlin writes as null: give it a value with ?:, or choose between classes."
                        } else {
                            "Choose between classes, or write a number."
                        }
                    if (factSays == Verdict.DIGITS) {
                        unsigned(where)
                    } else {
                        issue("Kotlin writes $shown into $where, where it is read as CSS, so escaping does not help. $advice")
                    }
                }

                Position.DATASTAR -> {
                    val attribute = landing.attribute ?: "a Datastar attribute"
                    val trap = trapOf(ip)?.let { datastarTrap(it, ip, landing, attributeValues, opts.prefix, fact) }
                    when {
                        trap != null -> trap
                        factSays == Verdict.DIGITS -> unsigned(attribute)
                        factSays == Verdict.UNSAFE -> datastarIssue(ip, attribute)
                        else -> null
                    }
                }

                Position.CODE -> {
                    val vouch = fixable && k in vouched.keys
                    val message =
                        if (rawElement(view, landing, starts[k]) == "style") {
                            "Kotlin writes $shown into a <style>, where it is read as CSS, so escaping does not help. " +
                                "Choose between classes, or write a number." +
                                if (vouch) " CSS you wrote yourself can be marked as Trusted(...) in interpolate { }." else ""
                        } else {
                            "Kotlin writes $shown into ${landing.attribute ?: "a script"}, which the browser runs, " +
                                "so escaping does not help. Put the value in a data-* attribute and read it from there."
                        }
                    issue(message + rewriteNote().takeIf { !vouch }.orEmpty(), fixes = if (vouch) listOf(rewriteAs()) else emptyList())
                }

                Position.STRUCTURE -> {
                    // `</h$n>` names the element `<h$n` opened; the opening one is judged.
                    val closing = closingTagBefore(view, ip.decodedStart)
                    val closes =
                        closing != null &&
                            s.interpolations.take(k).any {
                                it.text == ip.text && it.decodedStart > closing.length &&
                                    view.startsWith("<$closing", it.decodedStart - closing.length - 1)
                            }
                    when {
                        closes -> {
                            null
                        }

                        endTagAt(view, ip.decodedStart) -> {
                            issue(
                                "Kotlin writes $shown into an end tag, which takes no attributes: the browser drops them, " +
                                    "and a value from outside can still end the tag and write markup. Write the end tag from literals alone.",
                                severity,
                            )
                        }

                        unquotedValueBefore(view, ip.decodedStart) -> {
                            // Digits and words need the quotes alone; anything else is then a quoted value written as it is.
                            val enough = fact.scalar()
                            issue(
                                "Kotlin writes $shown into an attribute value without quotes, where a space starts a new attribute. " +
                                    "Quote the value." +
                                    if (enough) "" else " Quoting alone leaves the value as it is: escape it once it is quoted.",
                                if (enough) Severity.HINT else Severity.WARNING,
                                fixes =
                                    listOfNotNull(
                                        quoteFix(s, view, ip, operands)?.let { if (enough) it else it.copy(preferred = false) },
                                    ),
                            )
                        }

                        else -> {
                            issue(
                                "Kotlin writes $shown into the markup's own structure: a tag name, an attribute name " +
                                    "or a list of attributes. Fine for one chosen in code; a value from outside decides " +
                                    "the markup itself, and escaping cannot prevent that.",
                                severity,
                            )
                        }
                    }
                }
            } ?: continue
    }
    // The context action is for a string some issue of which offers the rewrite: one with a value to escape.
    if (!offered) return Judged(issues)
    // One title for the one edit, wherever it is offered; a string inside a template of another is named, as the
    // other's rewrite is offered at the same caret.
    val title =
        (if (vouched.isEmpty() && urlAsked) REFUSES_URL_TITLE else rewrite.title) +
            if (inner) ": ${shortened(src.substring(s.start, s.end))}" else ""
    val titled =
        issues.map { i ->
            if (i.fixes.none {
                    it.sharesTextWith(rewrite)
                }
            ) {
                i
            } else {
                i.copy(fixes = i.fixes.map { if (it.sharesTextWith(rewrite)) it.copy(title = title) else it })
            }
        }
    return Judged(titled, rewrite.copy(title = title))
}

/** The `<style>` or `<script>` whose body the value at [at] of the markup lands in, as the markup scan reads it, or null. */
private fun Analyzer.rawElement(
    view: String,
    landing: ValueLanding,
    at: Int,
): String? {
    if (landing.position != Position.CODE || landing.attribute != null) return null
    var open: String? = null
    for (tag in tokenize(view, false).tags) {
        if (tag.start >= at) break
        val name = tag.name.asciiLowercase()
        if (tag.closing && name == open) {
            open = null
        } else if (!tag.closing && (name == "style" || name == "script")) {
            open = name
        }
    }
    return open
}

/** Whether a value after [before] in the `content` of a `<meta>` starts the URL a refresh reads: `0;url=`. */
private fun Analyzer.refreshUrlStart(
    view: String,
    landing: ValueLanding,
    before: () -> String,
): Boolean =
    landing.attribute?.asciiLowercase() == "content" && landing.tagStart >= 0 && landing.valueStart >= 0 &&
        view.startsWith("<meta", landing.tagStart, ignoreCase = true) &&
        REFRESH_BEFORE_URL.matchEntire(decodeEntities(before()).text) != null

/** Whether the literal at [start] stands where Kotlin takes a constant alone: the argument of an annotation, or a `const val`. */
private fun Analyzer.constantOnly(
    src: String,
    start: Int,
): Boolean {
    val before = src.substring(maxOf(0, start - 400), start)
    if (CONST_VAL_BEFORE.containsMatchIn(before)) return true
    // The bracket the literal is an argument in, and what it belongs to.
    var depth = 0
    for (k in before.indices.reversed()) {
        val c = before[k]
        if (c == ')' || c == ']') depth++
        if ((c == '(' || c == '[') && depth-- == 0) return ANNOTATION_BEFORE.containsMatchIn(before.substring(0, k))
        // A line that goes on from no argument list is where the statement starts.
        if (c == '\n' && depth == 0 && before.lastNonSpaceBefore(k).let { it != ',' && it != '(' && it != '[' }) return false
    }
    return false
}

/** Where a value interpolate refuses stands, as the issue of another value names it. */
private fun Analyzer.refusedPlace(
    view: String,
    landing: ValueLanding,
    at: Int,
): String =
    landing.attribute ?: when {
        landing.position == Position.CODE -> "a <${rawElement(view, landing, at) ?: "script"}>"
        unquotedValueBefore(view, at) -> "an attribute value without quotes"
        else -> "the markup's structure"
    }

/** Whether [at] in the markup is inside an attribute value written without quotes: `x=$q`. */
private fun Analyzer.unquotedValueBefore(
    view: String,
    at: Int,
): Boolean {
    var k = at
    while (k > 0 && view[k - 1] !in UNQUOTED_STOP) k--
    while (k > 0 && view[k - 1] in " \t\n\u000C\r") k--
    return k > 0 && view[k - 1] == '='
}

/** The name an end tag has so far right before [at], `h` of `</h$n>`, or null when [at] does not follow one. */
private fun Analyzer.closingTagBefore(
    view: String,
    at: Int,
): String? {
    var k = at
    while (k > 0 && (view[k - 1].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ":_-" })) k--
    return if (k >= 2 && view.startsWith("</", k - 2)) view.substring(k, at) else null
}

/**
 * Whether `interpolate(...)` writes the value as the template does, escaped at most, so the
 * rewrite keeps the page: anything it admits where it lands, but not a `Trusted` outside element
 * text (it is not escaped there, so the rewrite would fix nothing), nor markup held as a String
 * in element text, which it would escape. A value that may be null is kept: a template writes
 * `null` for it, which no page means, and interpolate nothing.
 */
private fun Analyzer.interpolateKeeps(
    src: String,
    s: KotlinString,
    ip: Interpolation,
    told: ValueFact?,
    position: Position,
    opts: Reading,
): Boolean {
    val fact = told.plain()
    if (position == Position.DATASTAR || position == Position.STYLE) return fact.interpolateTakesIn(position)
    if (position !in ESCAPED_POSITIONS) return false
    // A value that holds a Trusted among other things is escaped as a whole anywhere but in element text.
    if (!told.isOther() && callsTrusted(src, ip.text)) return position == Position.TEXT
    if (fact.isMarkup()) return position != Position.TEXT || markupValued(src, s, ip)
    if (position == Position.TEXT && !fact.digitsOrBoolean() &&
        mayHoldMarkup(src, ip.text, s.start, opts.constants)
    ) {
        return false
    }
    return !(fact.isConstant() && position == Position.TEXT && ip.kind != InterpolationKind.SIMPLE && '<' in ip.text)
}

/**
 * What the analysis knows of a placeholder's value: the editor's fact when it gives one, which no rule
 * of the text overrides; otherwise what the text shows, of the placeholder or of each value an `if`, a
 * `when` or an `?:` in it can give.
 */
internal fun Analyzer.factOf(
    s: KotlinString,
    src: String,
    opts: Reading,
    ip: Interpolation,
): ValueFact? {
    opts.valueFact?.invoke(ip.start, ip.end)?.let { return it }
    val sdk = opts.modules
    val values = if (ip.kind == InterpolationKind.SIMPLE) listOf(TemplateValue(ip.text, false)) else templateValues(ip.text)
    val kinds = values.map { valueKind(src, it.text, s.start, opts.constants, it.nonNull, sdk) }
    if (kinds.all { it.isConstant() }) return ValueFact.CONSTANT
    if (isMarkup(src, s, ip, sdk) || markupCollection(src, ip.text, s.start, sdk)) return ValueFact.MARKUP
    return weakest(kinds)
}

/**
 * What a value written into the HTML as it is can do, and what to write instead: [interpolate]
 * and [trusted] say whether a fix may name `interpolate` and `Trusted` in this file, which
 * plain Kotlin and kotlinx.html stand in for where it may not, and [html] whether the project's
 * core has `Html` to declare.
 */
private fun Analyzer.unescapedMessage(
    shown: String,
    markup: Boolean,
    text: Boolean,
    interpolate: Boolean,
    trusted: Boolean,
    html: Boolean,
): String {
    // Markup an attribute holds is only characters, whose quotes end the value: that it is markup is the problem.
    if (markup) {
        return "Kotlin writes $shown into the HTML as it is, and it is markup: inside an attribute its quotes and tags " +
            "are only characters, and a quote ends the value. Write text there rather than markup."
    }
    val base =
        "Kotlin writes $shown into the HTML as it is. If it can hold text from outside, " +
            if (interpolate) {
                "mark it with + in interpolate { }, which escapes it"
            } else {
                "escape it before it goes in, or build this part with kotlinx.html, which escapes " +
                    if (text) "text" else "attribute values"
            }
    if (!text) return "$base."
    val built = if (!trusted) "this is fine" else (if (html) "declare it Html or " else "") + "wrap it in Trusted(...)"
    return "$base; if it is markup you built, $built."
}

/** A Kotlin value written into [attribute], which Datastar runs: an attribute of the markup, or the one a DSL call writes. */
internal fun Analyzer.datastarIssue(
    ip: Interpolation,
    attribute: String,
): Issue =
    Issue(
        ip.start,
        ip.end,
        "Kotlin writes ${valueShown(ip)} into $attribute, which Datastar runs as an expression or follows as a signal path. " +
            "Text from outside becomes code there, even inside quotes. " +
            datastarAdvice(attribute, "; ") +
            "only a number or a boolean is safe to write in.",
        Severity.WARNING,
        "unsafe-interpolation",
        Docs.EXPRESSIONS,
    )

/**
 * `$name` where a signal could stand: the Kotlin value or the signal of that name, which only the
 * author knows. Null when the placeholder sits in a JavaScript string or an attribute that takes no
 * expression, where no signal was ever possible and the value is what is judged.
 */
private fun Analyzer.datastarTrap(
    trap: Issue,
    ip: Interpolation,
    landing: ValueLanding,
    values: ExpressionValues,
    prefix: String,
    fact: ValueFact?,
): Issue? {
    val attribute = landing.attribute ?: return null
    if (landing.valueStart < 0) return null
    val spec = catalog.parseAttributeName(attribute.asciiLowercase(), prefix)?.spec
    if (spec?.valueKind != ValueKind.EXPRESSION) return null
    val value = values.value(landing.valueStart)
    if (values.inString(landing.valueStart, ip.decodedStart - landing.valueStart)) return null
    val name = ip.text
    val fixes = trap.fixes.filter { it.title.startsWith("Make it") || it.title.startsWith("Escape") }
    // data-signals:count="$initial" seeds a signal from the server, like the object form does.
    val whole = spec.name != "signals" && value.trim() == PLACEHOLDER
    // A number or a boolean is safe written in: what is left to ask is whether a signal was meant.
    if (fact != null && fact.digitsOrBoolean()) {
        val what = fact.described()
        return trap.copy(
            severity = Severity.HINT,
            fixes = fixes,
            message =
                "Kotlin writes \$$name here, $what from the server, which is safe in a Datastar expression. " +
                    "Meant as the signal \$$name, make the literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+), or escape as \${'$'}$name.",
        )
    }
    return trap.copy(
        severity = Severity.WARNING,
        fixes = fixes,
        message =
            if (whole) {
                "Kotlin interpolates \$$name here, as the whole Datastar expression. " +
                    "Meant as a server value, only a number or a boolean is safe there; " +
                    "meant as the signal \$$name, make the whole literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+), or escape as \${'$'}$name."
            } else {
                "Kotlin interpolates \$$name inside a Datastar expression. " +
                    "Meant as a server value, only a number or a boolean is safe there; " +
                    "meant as the signal \$$name, make the literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+) or escape as \${'$'}$name."
            },
    )
}
