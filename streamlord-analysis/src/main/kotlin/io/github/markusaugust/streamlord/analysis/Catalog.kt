package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol

/** The kind of argument a modifier takes. */
public enum class ModifierType { FLAG, DURATION, ENUM, INT, IDENT, IDENTS }

public data class Modifier(
    val name: String,
    val type: ModifierType,
    val flags: List<String> = emptyList(),
    val values: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
)

/**
 * How Datastar reads the key, which the browser has lowercased: [CAMEL] turns `foo-bar` into the
 * signal `fooBar`; [KEBAB] keeps it unless `__case` says otherwise (events, classes); [RAW] uses
 * it as it is (attributes, style properties).
 */
public enum class KeyCase { CAMEL, KEBAB, RAW }

public enum class ValueKind { EXPRESSION, SIGNAL, FILTER, TEXT, NONE }

public data class AttributeSpec(
    val name: String,
    val pro: Boolean,
    val forms: List<String>,
    val keyed: Boolean,
    val keyRequired: Boolean,
    val keyCase: KeyCase?,
    val valueKind: ValueKind,
    val kotlin: List<String>,
    val modifiers: List<Modifier>,
    val onlyOn: List<String>?,
    val doc: String,
)

public data class ActionSpec(
    val name: String,
    val pro: Boolean,
    val kind: String,
    val signature: String,
    val kotlin: String,
    val doc: String,
)

/** Which argument of a Streamlord call holds the Datastar string, and where its companions are. */
public data class CallSiteSpec(
    /** A positional index, or `null` for "the last positional string". */
    val arg: Int?,
    val named: String? = null,
    val selectorArg: String? = null,
    val modeArg: String? = null,
    val onlyIfStringArgs: Boolean = false,
) {
    val last: Boolean get() = arg == null
}

public data class FetchOption(
    val name: String,
    val type: String,
    val default: String,
)

/** A rendered attribute name such as `data-on:click__once__debounce.500ms`, taken apart. */
public data class ParsedAttribute(
    val base: String,
    val key: String?,
    val modifiers: List<ParsedModifier>,
    val spec: AttributeSpec?,
)

public data class ParsedModifier(
    val text: String,
    val name: String,
    val args: List<String>,
    /** Offset of the modifier's first character within the attribute name, after the `__`. */
    val offset: Int,
)

/**
 * The Datastar surface Streamlord knows, read from `catalog/datastar-<version>.json`: the single
 * source of truth that the SDK's tests bind to the DSL and the editors bind to their checks.
 */
public class Catalog private constructor(
    raw: JsonObject,
) {
    public val version: String = raw.string("version") ?: DatastarProtocol.VERSION
    public val prefix: String = raw.string("prefix") ?: "data-"
    public val aliasedPrefix: String = raw.string("aliasedPrefix") ?: "data-star-"
    public val attributes: List<AttributeSpec>
    public val actions: List<ActionSpec>
    public val fetchOptions: List<FetchOption>
    public val fetchEvents: List<String>
    public val patchModes: List<String>
    public val expressionCallSites: Map<String, CallSiteSpec>
    public val htmlCallSites: Map<String, CallSiteSpec>
    public val scriptCallSites: Map<String, CallSiteSpec>
    public val selectorCallSites: Map<String, CallSiteSpec>

    init {
        val groups = raw.obj("modifierGroups") ?: JsonObject.EMPTY
        attributes =
            raw.array("attributes")!!.objects().map { a ->
                AttributeSpec(
                    name = a.string("name")!!,
                    pro = a.boolean("pro") ?: false,
                    forms = a.array("forms")?.strings() ?: emptyList(),
                    keyed = a.boolean("keyed") ?: false,
                    keyRequired = a.boolean("keyRequired") ?: false,
                    keyCase = a.string("keyCase")?.let { KeyCase.valueOf(it.uppercase()) },
                    valueKind = ValueKind.valueOf((a.string("valueKind") ?: "none").uppercase()),
                    kotlin = a.array("kotlin")?.strings() ?: emptyList(),
                    modifiers =
                        (a.array("modifiers")?.objects() ?: emptyList()).flatMap { m ->
                            val ref = m.string("\$ref")
                            if (ref != null) (groups.array(ref)?.objects() ?: emptyList()).map(::toModifier) else listOf(toModifier(m))
                        },
                    onlyOn = a.array("onlyOn")?.strings(),
                    doc = a.string("doc") ?: "",
                )
            }
        actions =
            raw.array("actions")!!.objects().map { a ->
                ActionSpec(
                    name = a.string("name")!!,
                    pro = a.boolean("pro") ?: false,
                    kind = a.string("kind") ?: "",
                    signature = a.string("signature") ?: "",
                    kotlin = a.string("kotlin") ?: "",
                    doc = a.string("doc") ?: "",
                )
            }
        fetchOptions =
            (raw.array("fetchOptions")?.objects() ?: emptyList()).map {
                FetchOption(it.string("name") ?: "", it.string("type") ?: "", it.string("default") ?: "")
            }
        fetchEvents = raw.array("fetchEvents")?.strings() ?: emptyList()
        patchModes = raw.obj("sse")?.array("patchModes")?.strings() ?: emptyList()
        val sites = raw.obj("kotlinCallSites") ?: JsonObject.EMPTY
        expressionCallSites = callSites(sites.obj("expression"))
        htmlCallSites = callSites(sites.obj("html"))
        scriptCallSites = callSites(sites.obj("script"))
        selectorCallSites = callSites(sites.obj("selector"))
    }

    public val attributesByName: Map<String, AttributeSpec> = attributes.associateBy { it.name }
    public val attributesByKotlin: Map<String, AttributeSpec> = attributes.flatMap { a -> a.kotlin.map { it to a } }.toMap()
    public val actionsByName: Map<String, ActionSpec> = actions.associateBy { it.name }

    /** Every DSL function the analysis looks at, of every kind. */
    public val allCallSiteNames: Set<String> =
        expressionCallSites.keys + htmlCallSites.keys + scriptCallSites.keys + selectorCallSites.keys

    /** Parse a rendered attribute name such as `data-on:click__once__debounce.500ms` into its parts. */
    public fun parseAttributeName(
        name: String,
        prefix: String,
    ): ParsedAttribute? {
        if (!name.startsWith(prefix)) return null
        val rest = name.substring(prefix.length)
        val parts = rest.split("__")
        val head = parts[0]
        val colon = head.indexOf(':')
        val base = if (colon >= 0) head.substring(0, colon) else head
        val key = if (colon >= 0) head.substring(colon + 1) else null
        val modifiers = ArrayList<ParsedModifier>()
        var offset = prefix.length + head.length
        for (part in parts.drop(1)) {
            offset += 2
            val pieces = part.split(".")
            modifiers += ParsedModifier(text = part, name = pieces[0], args = pieces.drop(1), offset = offset)
            offset += part.length
        }
        return ParsedAttribute(base, key, modifiers, attributesByName[base])
    }

    private fun toModifier(m: JsonObject): Modifier =
        Modifier(
            name = m.string("name") ?: "",
            type = ModifierType.valueOf((m.string("type") ?: "flag").uppercase()),
            flags = m.array("flags")?.strings() ?: emptyList(),
            values = m.array("values")?.strings() ?: emptyList(),
            min = m.int("min"),
            max = m.int("max"),
        )

    private fun callSites(obj: JsonObject?): Map<String, CallSiteSpec> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, CallSiteSpec>()
        for ((name, value) in obj) {
            if (name.startsWith("\$")) continue
            val spec = value as? JsonObject ?: continue
            out[name] =
                CallSiteSpec(
                    arg = spec.int("arg"),
                    named = spec.string("named"),
                    selectorArg = spec.string("selectorArg"),
                    modeArg = spec.string("modeArg"),
                    onlyIfStringArgs = spec.boolean("onlyIfStringArgs") ?: false,
                )
        }
        return out
    }

    public companion object {
        /** The catalog bundled with this build, for the protocol version the SDK speaks. */
        public val default: Catalog by lazy { load(DatastarProtocol.VERSION) }

        /** Load the bundled catalog for a Datastar [version]. */
        public fun load(version: String): Catalog {
            val name = "/datastar-$version.json"
            val text =
                Catalog::class.java.getResourceAsStream(name)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("Catalog resource $name is missing from the classpath")
            return parse(text)
        }

        /** Read a catalog from its JSON text. */
        public fun parse(json: String): Catalog = Catalog(JsonParser.parseObject(json))
    }
}

/** Levenshtein distance, for "did you mean" hints. */
public fun distance(
    a: String,
    b: String,
): Int {
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val tmp = dp[j]
            dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = tmp
        }
    }
    return dp[b.length]
}
