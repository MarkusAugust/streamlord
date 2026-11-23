package io.github.markusaugust.streamlord.analysis

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtWhenExpression
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds what the analysis reads of a Kotlin file's structure to Kotlin's own parser. For each value: where it ends,
 * and whether a getter follows it. For each function of the file's top level or a block: what it gives. For each
 * local, parameter, loop and `catch` variable and `when` subject: that the analysis declares it, where it can first
 * and last be seen. A fact the analysis does not read at all is a difference too. Every Kotlin file git keeps is read
 * both ways; `-PfasitExtra=<dirs>` reads more beside them and reports it.
 */
class KotlinParserFasitTest {
    // The parser alone, which the K1 environment still brings: nothing here is compiled or resolved.
    @OptIn(K1Deprecation::class)
    private val factory by lazy {
        val configuration = CompilerConfiguration()
        configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        val environment =
            KotlinCoreEnvironment.createForProduction(Disposer.newDisposable(), configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        KtPsiFactory(environment.project)
    }

    @Test
    fun `the analysis reads where values and scopes end as the Kotlin parser does`() {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }.first {
                File(it, "settings.gradle.kts").isFile && File(it, "catalog").isDirectory
            }
        val found =
            trackedKotlin(root).flatMap { file ->
                differences(file.readKotlin()).map { it.copy(path = file.relativeTo(root).invariantSeparatorsPath) }
            }
        val report = File(root, "streamlord-analysis/build/reports/fasit.txt")
        report.parentFile.mkdirs()
        report.writeText(found.joinToString("\n", postfix = "\n") { it.report })

        // Other projects are read and reported beside the repository's own, and hold nothing here.
        val extra =
            System
                .getProperty("streamlord.fasit.extra")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
        if (extra.isNotEmpty()) {
            val more =
                extra.map(::File).flatMap { dir ->
                    kotlinFiles(dir).flatMap { f -> differences(f.readKotlin()).map { it.copy(path = f.path) } }
                }
            File(root, "streamlord-analysis/build/reports/fasit-extra.txt").writeText(more.joinToString("\n", postfix = "\n") { it.report })
            println("fasit: ${more.size} differences in ${extra.joinToString()}, in build/reports/fasit-extra.txt")
        }

        val known = File(root, KNOWN)
        // A fixture that is a piece of a file, not one, is reported and holds nothing.
        val held = found.filter { it.kind != "unparsed" }
        val keys = held.map { it.key }
        val heldCount =
            known
                .readLines()
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
        val foundCount = keys.groupingBy { it }.eachCount()
        val new = held.filter { (foundCount[it.key] ?: 0) > (heldCount[it.key] ?: 0) }
        val gone = heldCount.filter { (k, n) -> n > (foundCount[k] ?: 0) }.keys
        // `-PrecordFasit=true` takes out what is gone and nothing more; `-PrecordFasit=all` writes what is found, new
        // differences too, for the review of a pull request to weigh.
        when (System.getProperty("streamlord.fasit.record")) {
            "all" -> {
                return known.writeText(keys.sorted().joinToString("\n", postfix = "\n"))
            }

            "true" -> {
                val kept = heldCount.flatMap { (k, n) -> List(minOf(n, foundCount[k] ?: 0)) { k } }
                known.writeText(kept.sorted().joinToString("\n", postfix = "\n"))
            }
        }
        val message =
            buildString {
                if (new.isNotEmpty()) {
                    appendLine(
                        "The analysis reads ${new.size} places otherwise than Kotlin's parser where it did not:\n" +
                            new.joinToString("\n") { it.report },
                    )
                }
                if (gone.isNotEmpty() && System.getProperty("streamlord.fasit.record") != "true") {
                    appendLine(
                        "${gone.size} known differences are gone; take them out with -PrecordFasit=true:\n" + gone.joinToString("\n"),
                    )
                }
            }
        assertTrue(message.isEmpty(), message)
    }

    /** The Kotlin files git keeps under [root], so an untracked file of one's own holds nothing; all of them without git. */
    private fun trackedKotlin(root: File): List<File> =
        runCatching {
            val git = ProcessBuilder("git", "ls-files", "-z", "--", "*.kt").directory(root).redirectErrorStream(false).start()
            val out = git.inputStream.readBytes().decodeToString()
            check(git.waitFor(1, TimeUnit.MINUTES) && git.exitValue() == 0)
            out
                .split('\u0000')
                .filter { it.isNotEmpty() }
                .sorted()
                .map { File(root, it) }
                .filter { it.isFile }
        }.getOrElse { kotlinFiles(root) }

    private fun kotlinFiles(dir: File): List<File> =
        dir
            .walkTopDown()
            .onEnter { it.name !in setOf("build", "node_modules", ".git", ".gradle", ".claude", ".idea", "out") }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    /** Each place the analysis and the parser read [src] differently; for a file the parser finds errors in, that alone. */
    private fun differences(src: String): List<Difference> {
        val facts = fileFacts(src)
        val out = ArrayList<Difference>()
        val file = factory.createFile("Fasit.kt", src)
        if (PsiTreeUtil.hasErrorElements(file)) return listOf(Difference("", 1, "unparsed", "", "", "", "the parser finds errors in it"))

        fun difference(
            kind: String,
            name: PsiElement,
            detail: String,
        ) {
            val at = name.textOffset
            val text = src.substring(src.lastIndexOf('\n', at - 1) + 1, src.indexOf('\n', at).let { if (it < 0) src.length else it }).trim()
            // The declarations around it, by name: the same line in two objects is two places.
            val container =
                generateSequence(name.parent) { it.parent }
                    .takeWhile { it !is KtFile }
                    .filterIsInstance<KtNamedDeclaration>()
                    .filter { it.nameIdentifier != name }
                    .mapNotNull { it.name }
                    .toList()
                    .asReversed()
                    .joinToString(".")
            out += Difference("", src.substring(0, at).count { it == '\n' } + 1, kind, container, name.text, text, detail)
        }

        fun snippet(at: Int) =
            src.substring(maxOf(0, at - 20).coerceAtMost(src.length), minOf(src.length, at + 20).coerceAtLeast(0)).replace("\n", "⏎")

        // Where comments stand, as the parser reads them: a KDoc's `$name` is no code. A scope may end past the next
        // declaration's modifiers too, at `private fun` rather than before `private`; a value may not.
        val comment = BooleanArray(src.length)
        val between = BooleanArray(src.length)

        fun mark(e: PsiElement) {
            if (e is PsiComment || e is KtModifierList) {
                for (k in e.textRange.startOffset until e.textRange.endOffset) {
                    between[k] = true
                    if (e is PsiComment) comment[k] = true
                }
                if (e is PsiComment) return
            }
            var child = e.firstChild
            while (child != null) {
                mark(child)
                child = child.nextSibling
            }
        }
        mark(file)

        /** What the analysis wrote from [start] to [end], without the white space and comments around it. */
        fun written(
            start: Int,
            end: Int,
        ): String {
            var from = start
            val to = withoutTrailingComments(src, comment, end)
            while (from < to && (src[from].isWhitespace() || comment[from])) from++
            return src.substring(from, maxOf(from, to))
        }

        // The analysis declares a parameter just past its `(`, `,` or `{`, and a local at its name: the declaration of
        // the name that stands closest before it within what declares it is the one.
        fun declarationOf(
            name: PsiElement,
            owner: PsiElement,
        ): Declaration? {
            val at = name.textOffset
            return facts.names[name.text.removeSurrounding("`")]
                .orEmpty()
                .filter { it.at in owner.textRange.startOffset..at }
                .maxByOrNull { it.at }
        }

        fun scope(
            name: PsiElement?,
            owner: PsiElement,
            end: Int,
            local: Boolean,
        ) {
            name ?: return
            if (name.text == "_") return
            val d = declarationOf(name, owner)
            if (d == null) {
                difference("undeclared", name, "the analysis declares no ${name.text} here")
                return
            }
            // A local is seen from its name on, a parameter from what declares it.
            val first = if (local) name.textOffset else owner.textRange.startOffset
            if (d.start < first) difference("start", name, "analysis «${snippet(d.start)}» parser «${snippet(first)}»")
            if (!sameEnd(src, between, d.end, end)) {
                difference("scope", name, "analysis «${if (d.end == Int.MAX_VALUE) "never" else snippet(d.end)}» parser «${snippet(end)}»")
            }
        }

        /** Where a local declared in [parent] can last be seen: its block's end, a `when`'s, or a `do`'s condition's. */
        fun localEnd(parent: PsiElement?): Int? =
            when {
                parent is KtWhenExpression -> parent.textRange.endOffset
                parent is KtBlockExpression && parent.parent is KtDoWhileExpression -> parent.parent.textRange.endOffset
                parent is KtBlockExpression -> blockEnd(parent)
                else -> null
            }

        /**
         * What the modifiers of a property or a constructor's property say, as the parser reads them: whether it is a
         * constant, whether it is marked HTML, and whether another declaration may stand in for it, a member of [cls].
         */
        fun modifiers(
            declaration: KtCallableDeclaration,
            name: PsiElement,
            d: Declaration,
            cls: KtClassOrObject?,
        ) {
            val const = declaration.hasModifier(KtTokens.CONST_KEYWORD)
            if (d.constant != const) difference("const", name, "analysis ${if (d.constant) "reads" else "misses"} const")
            val html = declaration.annotationEntries.any { HTML_ANNOTATION.containsMatchIn(it.text) }
            if (d.html != html) difference("html", name, "analysis ${if (d.html) "reads" else "misses"} the HTML mark")
            val contract = cls is KtClass && cls.isInterface()
            val extended = contract || (cls is KtClass && EXTENDABLE.any { cls.hasModifier(it) })
            val own = declaration.hasModifier(KtTokens.OPEN_KEYWORD) || declaration.hasModifier(KtTokens.ABSTRACT_KEYWORD)
            val overridable = cls != null && (own || contract || (declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD) && extended))
            if (d.overridable != overridable) {
                difference("overridable", name, "analysis ${if (d.overridable) "reads" else "misses"} that another may stand in for it")
            }
        }

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    val name = property.nameIdentifier ?: return
                    val d = declarationOf(name, property)
                    val init = property.initializer
                    val value = d?.value
                    when {
                        // Nothing to compare: no value, or one the analysis declares no name for, which a scope tells.
                        d == null || init == null -> {}

                        value == null -> {
                            // A number or a boolean is held as one, not as a value, a call of `interpolate` or `Trusted`
                            // as markup, and a `var` or a lambda holds none.
                            val held =
                                d.literal != null || d.markup || d.trusted || init is KtConstantExpression || init is KtLambdaExpression
                            if (!property.isVar && !held) {
                                difference("value", name, "analysis reads none, parser «${init.text.oneLine()}»")
                            }
                        }

                        value.end < 0 -> {
                            difference("value", name, "analysis never ends it, parser «${init.text.oneLine()}»")
                        }

                        value.end < value.start -> {
                            difference("value", name, "analysis ends it at ${value.end}, before it starts at ${value.start}")
                        }

                        else -> {
                            val read = written(value.start, value.end)
                            if (read !=
                                init.text.trim()
                            ) {
                                difference("value", name, "analysis «${read.oneLine()}» parser «${init.text.oneLine()}»")
                            }
                            if (value.getter != (property.getter != null)) {
                                difference("getter", name, "analysis ${if (value.getter) "reads" else "misses"} a getter")
                            }
                        }
                    }
                    if (property.isLocal) localEnd(property.parent)?.let { scope(name, property, it, local = true) }
                    if (d != null) modifiers(property, name, d, (property.parent as? KtClassBody)?.parent as? KtClassOrObject)
                }

                override fun visitNamedFunction(function: KtNamedFunction) {
                    super.visitNamedFunction(function)
                    val name = function.nameIdentifier ?: return
                    val open = function.valueParameterList?.textRange?.startOffset ?: return
                    val body = function.bodyExpression ?: return
                    val declared = facts.functions[name.text.removeSurrounding("`")].orEmpty().firstOrNull { it.open == open }
                    val values = declared?.values.orEmpty()
                    // What the function gives: its expression, or the value of each `return` of its own, in order.
                    val returns = ArrayList<KtReturnExpression>()
                    if (function.hasBlockBody()) {
                        body.accept(
                            object : KtTreeVisitorVoid() {
                                override fun visitNamedFunction(inner: KtNamedFunction) = Unit

                                override fun visitReturnExpression(r: KtReturnExpression) {
                                    returns += r
                                    super.visitReturnExpression(r)
                                }
                            },
                        )
                    }
                    // A `return` to a label leaves the function unread.
                    if (returns.any { it.getTargetLabel() != null }) return
                    val given =
                        if (function.hasBlockBody()) {
                            returns.map {
                                it.returnedExpression
                                    ?.text
                                    ?.trim()
                                    .orEmpty()
                            }
                        } else {
                            listOf(
                                body.text.trim(),
                            )
                        }
                    if (values.isEmpty()) {
                        // What a function of the top level or of a block gives, written with its type, is read, unless
                        // it is marked HTML; a member another may stand in for is not.
                        val member = function.parent !is KtFile && !function.isLocal
                        if (!member && function.typeReference != null && declared?.html.isNullOrEmpty() && given.isNotEmpty()) {
                            difference("returns", name, "analysis reads none, parser «${given.joinToString(" | ").oneLine()}»")
                        }
                        return
                    }
                    if (values.any { it.end < it.start }) {
                        difference("returns", name, "analysis never ends one of them")
                        return
                    }
                    val read = values.map { written(it.start, it.end) }
                    if (read != given) {
                        difference(
                            "returns",
                            name,
                            "analysis «${read.joinToString(" | ").oneLine()}» parser «${given.joinToString(" | ").oneLine()}»",
                        )
                    }
                }

                override fun visitDestructuringDeclaration(multi: KtDestructuringDeclaration) {
                    super.visitDestructuringDeclaration(multi)
                    val end = localEnd(multi.parent) ?: return
                    for (entry in multi.entries) scope(entry.nameIdentifier, multi, end, local = true)
                }

                override fun visitParameter(parameter: KtParameter) {
                    super.visitParameter(parameter)
                    val owner = parameter.parent?.parent ?: return
                    // A constructor's property is declared, and its modifiers read, as a member of its class.
                    if (owner is KtPrimaryConstructor && parameter.hasValOrVar()) {
                        val name = parameter.nameIdentifier ?: return
                        val d = declarationOf(name, owner)
                        if (d == null) difference("undeclared", name, "the analysis declares no ${name.text} here")
                        d?.let { modifiers(parameter, name, it, owner.getContainingClassOrObject()) }
                        return
                    }
                    val end =
                        when (owner) {
                            is KtNamedFunction -> if (owner.bodyExpression == null) return else owner.textRange.endOffset
                            is KtFunctionLiteral -> owner.textRange.endOffset - 1
                            is KtCatchClause -> owner.textRange.endOffset
                            else -> return
                        }
                    val entries = parameter.destructuringDeclaration?.entries
                    if (entries != null) {
                        for (entry in entries) scope(entry.nameIdentifier, owner, end, local = false)
                    } else {
                        scope(parameter.nameIdentifier, owner, end, local = false)
                    }
                }

                override fun visitForExpression(expression: KtForExpression) {
                    super.visitForExpression(expression)
                    val p = expression.loopParameter ?: return
                    val end = expression.textRange.endOffset
                    p.destructuringDeclaration?.entries?.forEach { scope(it.nameIdentifier, expression, end, local = false) }
                        ?: scope(p.nameIdentifier, expression, end, local = false)
                }
            },
        )
        return out.distinctBy { listOf(it.line, it.name, it.kind, it.detail) }
    }
}

/** Where the facts are recorded that the analysis reads otherwise than the parser, one [Difference.key] a line. */
private const val KNOWN = "streamlord-analysis/src/test/resources/fasit-known.txt"

/** One place the analysis reads otherwise than the parser: in a file, of a [kind], for a [name] on a [line]. */
private data class Difference(
    val path: String,
    val line: Int,
    val kind: String,
    /** The declarations around it, by name, `Page.render`. */
    val container: String,
    val name: String,
    /** The line the name is declared on, which keeps the key when lines above it come or go. */
    val text: String,
    /** What each reads: another wrong reading of a known place is a new difference. */
    val detail: String,
) {
    val key: String get() = "$path | $kind | $container | $name | $text | $detail"
    val report: String get() = "$path:$line: $kind of $name: $detail"
}

private fun File.readKotlin(): String = readText().replace("\r\n", "\n")

/**
 * Whether the analysis's end [read] and the parser's [parsed] are the same place: nothing but white space, comments,
 * the next declaration's modifiers, a `;` and the one bracket that closes the scope stands between them.
 */
private fun sameEnd(
    src: String,
    between: BooleanArray,
    read: Int,
    parsed: Int,
): Boolean {
    val lo = minOf(read, parsed).coerceAtMost(src.length)
    val hi = maxOf(read, parsed).coerceAtMost(src.length)
    var brackets = 0
    for (k in lo until hi) {
        val c = src[k]
        if (between[k] || c.isWhitespace() || c == ';') continue
        if (c == '}' || c == ')') {
            if (++brackets > 1) return false
            continue
        }
        return false
    }
    return true
}

/** Where the closing brace of [block] stands: a lambda's body leaves its braces to the lambda. */
private fun blockEnd(block: KtBlockExpression): Int = (block.parent as? KtFunctionLiteral ?: block).textRange.endOffset - 1

/** Just past the last code before [end], past white space and comments. */
private fun withoutTrailingComments(
    src: String,
    comment: BooleanArray,
    end: Int,
): Int {
    var k = end.coerceAtMost(src.length)
    while (k > 0 && (src[k - 1].isWhitespace() || comment[k - 1])) k--
    return k
}

/** The modifiers by which a class can be extended. */
private val EXTENDABLE = listOf(KtTokens.OPEN_KEYWORD, KtTokens.ABSTRACT_KEYWORD, KtTokens.SEALED_KEYWORD)

/** One line, cut to 200 characters: enough to tell two readings apart. */
private fun String.oneLine(): String = replace("\n", "⏎").let { if (it.length > 200) it.take(197) + "..." else it }
