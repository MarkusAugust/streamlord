// r7/probes6: h11i-open-typearg-fun
object Case909 {
open class P {
    open @A<String> fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes6: h11j-mark-typearg-fun
object Case910 {
@Language("HTML") @A<String> fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes6: h11k-open-backtick-val
object Case911 {
open class P {
    open @`A` val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h11l-mark-backtick-val
object Case912 {
@Language("HTML") @`A` val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes6: h11m-mark-dotted-backtick-val
object Case913 {
@Language("HTML") @a.`b` val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes7: k01-ctor-after-typeparams-annotation
object Case914 {
class P<T> @A() (val title: String) {
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: k01b-ctor-after-typeparams-annotation
object Case915 {
class P<T> @A()(val title: String) {
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1a-brace-mark-private-newline-val
object Case921 {
fun a() {} @Language("HTML") private
val title: String = load()
fun page() = "<p>$title</p>"
}

// r7/probes7: m1a-brace-mark-private-newline
object Case922 {
fun a() {} @Language("HTML") private
fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes7: m1a-brace-open-annotation-call
object Case923 {
open class P {
    fun a() {} open @A(f(1)) val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1a-brace-open-plain
object Case924 {
open class P {
    fun a() {} open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1a-brace-private-newline
object Case925 {
fun a() {} private
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes7: m1b-backtick-mark-private-newline
object Case927 {
@`A` @Language("HTML") private
val title: String = load()
fun page() = "<p>$title</p>"
}

// r7/probes7: m1b-backtick-open-newline
object Case928 {
open class P {
    @`A` open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1b-plain-open-newline
object Case929 {
open class P {
    @A open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1b-space-args-open-newline
object Case930 {
open class P {
    @A (1) open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1b-typearg-open-fun-newline
object Case932 {
open class P {
    @A<String> open
    fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes7: m1b-typearg-open-newline
object Case933 {
open class P {
    @A<String> open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1b-typearg-private-newline
object Case934 {
@A<String> private
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes7: m3-infix-annot
object Case935 {
fun a() = b private const @A val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes7: m3-infix-open2
object Case937 {
open class P {
    fun z() = a private open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: n1-private-fun-interface-const
object Case939 {
private fun interface Q {
    fun run()
    companion object { const val title = "<b>x</b>" }
}
}

// r7/probes7: n1-private-fun-interface
object Case940 {
private fun interface Q {
    fun run()
    val title: String get() = "<b>x</b>"
}
}

// r7/probes8: p1-brace-open-13
object Case941 {
open class P {
    fun a() {} open @A @A @A @A @A @A @A @A @A @A @A @A @A val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p1-linestart-open-13
object Case942 {
open class P {
    open @A @A @A @A @A @A @A @A @A @A @A @A @A val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p1-semi-private-12
object Case943 {
val a = 1; private @A @A @A @A @A @A @A @A @A @A @A @A val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p1-semi-private-13
object Case944 {
val a = 1; private @A @A @A @A @A @A @A @A @A @A @A @A @A val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p1-value-const-13-mods
object Case945 {
val a = 1; const internal internal internal internal internal internal internal internal internal internal internal internal internal val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p1-value-mark-13
object Case946 {
val a = 1; @Language("HTML") @A @A @A @A @A @A @A @A @A @A @A @A @A val title: String = load()
fun page() = "<p>$title</p>"
}

// r7/probes8: p1-value-open-12
object Case947 {
open class P {
    val a = 1 open @A @A @A @A @A @A @A @A @A @A @A @A val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p1-value-open-13
object Case948 {
open class P {
    val a = 1 open @A @A @A @A @A @A @A @A @A @A @A @A @A val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p1-value-private-13-open
object Case949 {
open class P {
    val a = 1; private @A @A @A @A @A @A @A @A @A @A @A @A @A open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-brace-mark-newline-fun
object Case950 {
fun a() {} @Language("HTML")
fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes8: p2-infix-word-operand-getter
object Case954 {
class P {
    val title = b.let { } open value
        get() = "<i>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-lambda-infix-const
object Case956 {
val a = b { } const value
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p2-let-infix-two-private
object Case962 {
val a = b.let { } private value
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p3-mark-fun-interface
object Case972 {
@Language("HTML") fun interface Q { fun title(): String }
fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes8: p3-open-fun-abstract-then-val
object Case973 {
abstract class P {
    abstract fun a(): Int
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p3-open-fun-interface-member
object Case974 {
class P {
    private fun interface Q { fun run() }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p3-open-fun-newline-interface
object Case975 {
open class P {
    private fun
    interface Q { val title: String get() = "<b>x</b>" }
}
}

// r7/probes8: p3-private-fun-then-val
object Case977 {
private fun a() = 1
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p3-val-anon-fun-getter
object Case978 {
class P {
    val title = fun() = "<b>x</b>"
        get() = field
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p3-val-infix-fun-getter
object Case979 {
class P {
    val title = a to fun() = 1
        get() = field
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p3-val-then-private-fun-get
object Case981 {
class P {
    val title = "<b>x</b>"
    private fun get() = "<i>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-enum-semi-newline-private
object Case982 {
enum class E {
    A, B;
    private val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-enum-semi-open-newline
object Case983 {
enum class E {
    A, B; open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-const-newline
object Case985 {
val a = 1; const
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p4-semi-mark-newline-fun
object Case986 {
val a = 1; @Language("HTML")
fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes8: p4-semi-mark-private-newline
object Case987 {
val a = 1; @Language("HTML") private
fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes8: p4-semi-open-annotation-bracket
object Case988 {
open class P {
    val a = 1; open @A("(") val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-open-annotation-call
object Case989 {
open class P {
    val a = 1; open @A(f(1)) val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-open-annotation-group
object Case990 {
open class P {
    val a = 1; open @[A B] val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-open-newline
object Case992 {
open class P {
    val a = 1; open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-open-plain
object Case993 {
open class P {
    val a = 1; open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-open-typearg
object Case994 {
open class P {
    val a = 1; open @A<B, C> val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-private-getter
object Case995 {
class P {
    val title = "<b>x</b>"; private get() = "<i>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-private-newline
object Case996 {
val a = 1; private
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p5-long-const-before
object Case999 {
const @Query("""SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
""") val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p5-long-fun-value-open-before
object Case1001 {
open class P {
    open @Query("""SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
""")
    fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes8: p5-long-getter
object Case1004 {
class P {
    val title = "<b>x</b>"
        @Query("""SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
SELECT a FROM b WHERE (c = d)
""") get() = "<i>"
    fun page() = "<p>$title</p>"
}
}

