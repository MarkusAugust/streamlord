package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.client.regex

/**
 * Markup an element patch carries that Datastar's client would not patch as written
 * (patchElements.ts), found in one walk.
 *
 * - Without a selector, `outer` and `replace` put each top-level element in place of the page's
 *   element with its id; one without an id it skips, with only a console warning. Judged only when
 *   asked, by `Streamlord(guardElements = true)`: the SDK specification's own tests send
 *   `<div>Merge</div>` that way, so an event must be able to carry it.
 * - In the svg and mathml namespaces the markup is parsed inside an `<svg>` or a `<math>` and only
 *   what stays in it is patched, so an HTML element there, which the parser closes that root for,
 *   is dropped with all that follows it.
 * - In the html namespace an SVG or MathML element outside its root is parsed as an HTML element,
 *   which draws nothing.
 *
 * Not a parser: it follows the open elements by their end tags, so where the browser closes an
 * element on its own, as a `<p>` before a `<div>`, it may judge less than there is, never more.
 */
internal object PatchMarkup {
    // A document end tag outside an svg has the client patch the page's own html, head or body.
    private val SVG = regex("(?s)<svg(?:\\s[^>]*>|>).*?</svg>", RegexOption.IGNORE_CASE)
    private val DOCUMENT_END = regex("</(?:body|head|html)>")

    fun problem(
        elements: String?,
        selector: String?,
        mode: ElementPatchMode,
        namespace: ElementNamespace,
        ids: Boolean = false,
    ): String? {
        if (elements == null || mode == ElementPatchMode.REMOVE) return null
        // Most patches are HTML with no SVG or MathML name in them, and need no walk.
        if (!ids && namespace == ElementNamespace.HTML && !FOREIGN_NAME.containsMatchIn(elements)) return null
        val byId =
            ids &&
                selector == null &&
                (mode == ElementPatchMode.OUTER || mode == ElementPatchMode.REPLACE) &&
                !DOCUMENT_END.containsMatchIn(elements.replace(SVG, ""))
        val walk = Walk(namespace, byId)
        walkMarkup(
            elements,
            walk,
            if (namespace ==
                ElementNamespace.SVG
            ) {
                NS_SVG
            } else if (namespace == ElementNamespace.MATHML) {
                NS_MATH
            } else {
                NS_HTML
            },
        )
        return walk.problem
    }

    // An open element, and whether what it holds is parsed as SVG or MathML.
    private class Open(
        val name: String,
        val holdsForeign: Boolean,
    )

    private class Walk(
        private val namespace: ElementNamespace,
        private val byId: Boolean,
    ) : MarkupVisitor {
        var problem: String? = null
        private val open = ArrayList<Open>()
        private var id = false

        override fun attribute(
            tag: String,
            name: String,
            nameStart: Int,
            valueStart: Int,
            valueEnd: Int,
            quoted: Boolean,
        ) {
            if (name.equals("id", ignoreCase = true) && valueStart >= 0 && valueEnd > valueStart) id = true
        }

        override fun startTag(
            name: String,
            start: Int,
            end: Int,
            selfClosing: Boolean,
            rawText: Boolean,
        ) {
            val hasId = id
            id = false
            val foreign = open.lastOrNull()?.holdsForeign ?: (namespace != ElementNamespace.HTML)
            val exits = foreign && name in EXITS_FOREIGN
            // The parser closes the foreign elements for it, up to HTML or a place that holds HTML;
            // with none, it closes the root a patch in the svg or mathml namespace is parsed in.
            if (exits) while (open.lastOrNull()?.holdsForeign == true) open.removeAt(open.size - 1)
            if (problem == null) problem = problem(name, hasId, foreign && !exits, exits && open.isEmpty())
            val elementForeign = (foreign && !exits) || name == "svg" || name == "math"
            val holdsForeign = if (foreign && !exits) name !in INTEGRATION_POINTS else name == "svg" || name == "math"
            if (!(if (elementForeign) selfClosing else name in VOID)) open += Open(name, holdsForeign)
        }

        private fun problem(
            name: String,
            hasId: Boolean,
            foreign: Boolean,
            leavesRoot: Boolean,
        ): String? =
            when {
                leavesRoot && namespace != ElementNamespace.HTML -> {
                    val root = if (namespace == ElementNamespace.SVG) "svg" else "math"
                    "<$name> is HTML, and the client parses a patch in the ${namespace.wire} namespace inside an <$root>, " +
                        "which an HTML element closes: it and all that follows it are not patched. Put HTML in " +
                        "${if (root == "svg") "a <foreignObject>" else "an <mtext>"}, or send it in a patch of its own"
                }

                !foreign && name in SVG_ELEMENTS -> {
                    "<$name> is an SVG element, and outside an <svg> the browser makes it an HTML element that draws " +
                        "nothing. Send it with namespace = ElementNamespace.SVG, or inside an <svg>"
                }

                !foreign && name in MATHML_ELEMENTS -> {
                    "<$name> is a MathML element, and outside a <math> the browser makes it an HTML element that " +
                        "shows nothing. Send it with namespace = ElementNamespace.MATHML, or inside a <math>"
                }

                byId && open.isEmpty() && !hasId && name !in DOCUMENT -> {
                    "<$name> has no id. Without a selector, Datastar's client puts each top-level element in place " +
                        "of the page's element with its id, and skips one without (PatchElementsNoTargetsFound). " +
                        "Give it an id, or name a selector"
                }

                else -> {
                    null
                }
            }

        // The nearest open element of the name is closed, never one past a table or a template, as
        // the browser closes none there.
        override fun endTag(
            name: String,
            start: Int,
            end: Int,
        ) {
            for (k in open.indices.reversed()) {
                if (open[k].name == name) {
                    while (open.size > k) open.removeAt(open.size - 1)
                    return
                }
                if (open[k].name in BOUNDARIES) return
            }
        }
    }

    private val FOREIGN_NAME by lazy {
        regex("<(?:${(SVG_ELEMENTS + MATHML_ELEMENTS).joinToString("|")})[\\t\\n\\u000C\\r />]", RegexOption.IGNORE_CASE)
    }
    private val DOCUMENT = setOf("html", "head", "body")
    private val BOUNDARIES = setOf("table", "td", "th", "caption", "template", "object", "marquee", "applet")

    // Where an svg or a math holds HTML again.
    private val INTEGRATION_POINTS = setOf("foreignobject", "desc", "title", "mi", "mo", "mn", "ms", "mtext", "annotation-xml")

    // The SVG and MathML elements HTML has no element of the same name for, lowercased as the walk reports them.
    private val SVG_ELEMENTS =
        setOf(
            "animate",
            "animatemotion",
            "animatetransform",
            "circle",
            "clippath",
            "defs",
            "ellipse",
            "feblend",
            "fecolormatrix",
            "fecomponenttransfer",
            "fecomposite",
            "feconvolvematrix",
            "fediffuselighting",
            "fedisplacementmap",
            "fedistantlight",
            "fedropshadow",
            "feflood",
            "fefunca",
            "fefuncb",
            "fefuncg",
            "fefuncr",
            "fegaussianblur",
            "feimage",
            "femerge",
            "femergenode",
            "femorphology",
            "feoffset",
            "fepointlight",
            "fespecularlighting",
            "fespotlight",
            "fetile",
            "feturbulence",
            "filter",
            "foreignobject",
            "g",
            "line",
            "lineargradient",
            "marker",
            "mask",
            "metadata",
            "mpath",
            "path",
            "pattern",
            "polygon",
            "polyline",
            "radialgradient",
            "rect",
            "set",
            "stop",
            "switch",
            "symbol",
            "text",
            "textpath",
            "tspan",
            "use",
            "view",
        )
    private val MATHML_ELEMENTS =
        setOf(
            "annotation",
            "annotation-xml",
            "maction",
            "menclose",
            "merror",
            "mfrac",
            "mi",
            "mmultiscripts",
            "mn",
            "mo",
            "mover",
            "mpadded",
            "mphantom",
            "mprescripts",
            "mroot",
            "mrow",
            "ms",
            "mspace",
            "msqrt",
            "mstyle",
            "msub",
            "msubsup",
            "msup",
            "mtable",
            "mtd",
            "mtext",
            "mtr",
            "munder",
            "munderover",
            "semantics",
        )
}
