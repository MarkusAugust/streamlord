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
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds what the analysis reads of a Kotlin file's structure to Kotlin's own parser: where a value ends, and where a
 * parameter, a local or a loop's variable can last be seen. Every Kotlin file of the repository is read both ways,
 * and `-PfasitExtra=<dirs>` adds more.
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
            kotlinFiles(root).flatMap { file ->
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
        val keys = found.map { it.key }.sorted()
        if (System.getProperty("streamlord.record") == "true") {
            known.writeText(keys.joinToString("\n", postfix = "\n"))
            return
        }
        val held = known.readLines().filter { it.isNotBlank() }
        val heldCount = held.groupingBy { it }.eachCount()
        val foundCount = keys.groupingBy { it }.eachCount()
        val new = found.filter { (foundCount[it.key] ?: 0) > (heldCount[it.key] ?: 0) }
        val gone = heldCount.filter { (k, n) -> n > (foundCount[k] ?: 0) }.keys
        val message =
            buildString {
                if (new.isNotEmpty()) {
                    appendLine(
                        "The analysis reads ${new.size} places otherwise than Kotlin's parser where it did not:\n" +
                            new.joinToString("\n") { it.report },
                    )
                }
                if (gone.isNotEmpty()) {
                    appendLine(
                        "${gone.size} known differences are gone; record $KNOWN anew with -PrecordVerdicts=true:\n" +
                            gone.joinToString(
                                "\n",
                            ),
                    )
                }
            }
        assertTrue(message.isEmpty(), message)
    }

    private fun kotlinFiles(dir: File): List<File> =
        dir
            .walkTopDown()
            .onEnter { it.name !in setOf("build", "node_modules", ".git", ".gradle", ".claude", ".idea", "out") }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    /** Each place the analysis and the parser read [src] differently; none for a file the parser finds errors in. */
    private fun differences(src: String): List<Difference> {
        val facts = fileFacts(src)
        val declared =
            facts.names.values
                .flatten()
                .associateBy { it.at }
        val out = ArrayList<Difference>()

        fun difference(
            kind: String,
            name: PsiElement,
            detail: String,
        ) {
            val at = name.textOffset
            val text = src.substring(src.lastIndexOf('\n', at - 1) + 1, src.indexOf('\n', at).let { if (it < 0) src.length else it }).trim()
            out += Difference("", src.substring(0, at).count { it == '\n' } + 1, kind, name.text, text, detail)
        }

        fun snippet(at: Int) = src.substring(maxOf(0, at - 20), minOf(src.length, at + 20)).replace("\n", "⏎")

        val file = factory.createFile("Fasit.kt", src)
        if (PsiTreeUtil.hasErrorElements(file)) return emptyList()
        // Where comments and the next declaration's modifiers stand, as the parser reads them: a KDoc's `$name` is no
        // code, and a scope that ends at `private fun` rather than before `private` ends at the same place.
        val comment = BooleanArray(src.length)

        fun mark(e: PsiElement) {
            if (e is PsiComment || e is KtModifierList) {
                for (k in e.textRange.startOffset until e.textRange.endOffset) comment[k] = true
                return
            }
            var child = e.firstChild
            while (child != null) {
                mark(child)
                child = child.nextSibling
            }
        }
        mark(file)

        fun scope(
            name: PsiElement?,
            end: Int,
        ) {
            val at = name?.textOffset ?: return
            val d = declared[at] ?: return
            if (!sameEnd(src, comment, d.end, end)) {
                difference("scope", name, "analysis «${if (d.end == Int.MAX_VALUE) "never" else snippet(d.end)}» parser «${snippet(end)}»")
            }
        }

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    val name = property.nameIdentifier ?: return
                    val d = declared[name.textOffset]
                    val init = property.initializer
                    val value = d?.value
                    if (init != null && value != null && value.end >= 0) {
                        val read = src.substring(value.start, withoutTrailingComments(src, comment, value.end)).trim()
                        if (read != init.text.trim()) {
                            difference("value", name, "analysis «${read.oneLine()}» parser «${init.text.oneLine()}»")
                        }
                    }
                    if (property.isLocal) (property.parent as? KtBlockExpression)?.let { scope(name, blockEnd(it)) }
                }

                override fun visitDestructuringDeclaration(multi: KtDestructuringDeclaration) {
                    super.visitDestructuringDeclaration(multi)
                    val block = multi.parent as? KtBlockExpression ?: return
                    for (entry in multi.entries) scope(entry.nameIdentifier, blockEnd(block))
                }

                override fun visitParameter(parameter: KtParameter) {
                    super.visitParameter(parameter)
                    val owner = parameter.parent?.parent
                    val end =
                        when (owner) {
                            is KtNamedFunction -> if (owner.bodyExpression == null) return else owner.textRange.endOffset
                            is KtFunctionLiteral -> owner.textRange.endOffset - 1
                            is KtForExpression -> owner.textRange.endOffset
                            else -> return
                        }
                    if (parameter.destructuringDeclaration != null) {
                        for (entry in parameter.destructuringDeclaration!!.entries) scope(entry.nameIdentifier, end)
                    } else {
                        scope(parameter.nameIdentifier, end)
                    }
                }

                override fun visitForExpression(expression: KtForExpression) {
                    super.visitForExpression(expression)
                    val p = expression.loopParameter ?: return
                    p.destructuringDeclaration?.entries?.forEach { scope(it.nameIdentifier, expression.textRange.endOffset) }
                        ?: scope(p.nameIdentifier, expression.textRange.endOffset)
                }
            },
        )
        return out.distinctBy { it.line to it.name to it.kind }
    }
}

/** Where the facts are recorded that the analysis reads otherwise than the parser, one [Difference.key] a line. */
private const val KNOWN = "streamlord-analysis/src/test/resources/fasit-known.txt"

/** One place the analysis reads otherwise than the parser: in a file, of a [kind], for a [name] on a [line]. */
private data class Difference(
    val path: String,
    val line: Int,
    val kind: String,
    val name: String,
    /** The line the name is declared on, which keeps the key when lines above it come or go. */
    val text: String,
    val detail: String,
) {
    val key: String get() = "$path | $kind | $name | $text"
    val report: String get() = "$path:$line: $kind of $name: $detail"
}

private fun File.readKotlin(): String = readText().replace("\r\n", "\n")

/**
 * Whether the analysis's end [read] and the parser's [parsed] are the same place: nothing but white space, comments,
 * a `;` and the one bracket that closes the scope stands between them.
 */
private fun sameEnd(
    src: String,
    comment: BooleanArray,
    read: Int,
    parsed: Int,
): Boolean {
    val lo = minOf(read, parsed).coerceAtMost(src.length)
    val hi = maxOf(read, parsed).coerceAtMost(src.length)
    var brackets = 0
    for (k in lo until hi) {
        val c = src[k]
        if (comment[k] || c.isWhitespace() || c == ';') continue
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
    var k = end
    while (k > 0 && (src[k - 1].isWhitespace() || comment[k - 1])) k--
    return k
}

private fun String.oneLine(): String = replace("\n", "⏎").let { if (it.length > 80) it.take(77) + "..." else it }
