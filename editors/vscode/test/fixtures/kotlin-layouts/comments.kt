import org.intellij.lang.annotations.Language

// rev309/cases1: a285_param_comment
object Case7 {
fun card(@Language("HTML") /* the body */ body: String, title: String = "Hi <b>x</b>") = body
}

// rev309/cases1: a285_mark_comment
object Case11 {
// language=HTML
@JvmName("x") val page = "Hi <b>x</b>"
}

// rev309/cases1: c283_loop_comment_then_brace
object Case58 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) // each row
    {
        println(n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_ends_comment
object Case74 {
fun outer(n: String) {
    fun size(n: Int) = pad(n) // the size
    println("<p>$n</p>")
}
}

// rev309/cases2: r_language_comment_layouts
object Case84 {
class V(val name: String) {
    // language=HTML
    val a = "A <b>$name</b>"

    //language=HTML
    @JvmField val b = "B <b>$name</b>"

    /* language=HTML */ val c = "C <b>$name</b>"
}
}

// rev309/cases3: o_string_after_mark_in_comment
object Case115 {
@Language("HTML") // "quoted"
val page = "Hi <b>x</b>"
}

// rev309/cases4: p_loop_brace_after_comment_block
object Case156 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) /* each */ {
        println(n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases4: p_value_comment_after_bang
object Case170 {
fun f(q: Any?) {
    val a = q!! // forced
    val title = "<b>$a</b>"
}
}

// rev309/cases4: p_value_question_comment
object Case171 {
fun f(q: Any?) {
    val a = q as String? /* nullable */
    val title = "<b>$a</b>"
}
}

// rev309/cases4: p_value_as_q_comment
object Case172 {
fun f(q: Any?) {
    val a = q as? // the type
        String
    val title = "<b>$a</b>"
}
}

// rev309/cases4: p_value_else_comment
object Case173 {
fun f(n: Int) {
    val a = if (n > 0) "a" else // the other
        "b"
    val title = "<b>$a</b>"
}
}

// rev309/cases6: s_loop_stmt_then_comment_then_literal
object Case191 {
fun f(n: String, rows: List<Int>) = run {
    for (n: Int in rows) println(n)
    // done
    "<p>$n</p>".also(::println)
}
}

// rev309/cases6: s_value_paren_line_comment
object Case193 {
fun f(n: Int) {
    val a = if (n > 0) // positive
        "a" else "b"
    val t = "<b>$a</b>"
}
}

// r4mark/cases: m14_comment_marks.kt
object Case211 {
// language=HTML
val x = render("a")

// language=HTML
val y = "Hey <b>x</b>"

// language=HTML
fun f(n: String) = "Ho <b>$n</b>"

// language=HTML
fun g() = "Hi <b>x</b>"

fun k() {
    // language=HTML
    val z = "Local <b>x</b>"
    val w = "after"
}
}

// r4mark/cases: m15_comment_layouts.kt
object Case212 {
class P(val name: String) {
    // language=HTML
    val a: String
        get() = "Getter <b>$name</b>"

    // language=HTML
    var b: String = "Var <b>$name</b>"

    // language=HTML
    val c by lazy { "Lazy <b>$name</b>" }

    // language=HTML
    fun d(n: String): String {
        return "Ret <b>$n</b>"
    }

    // language=HTML
    fun e(n: String) =
        "Next <b>$n</b>"
}
}

// r4mark/cases: l14_comment_after_postfix.kt
object Case238 {
fun f(x: String, xs: List<Int>) {
    var n = 0
    for (x in xs) n++ // count
    println("<p>$x</p>")
    for (x in xs) n-- /* down */
    println("<p>$x</p>")
}
}

// r4mark/cases2: k03_comment_in_value.kt
object Case242 {
@Language("HTML")
fun f(n: String) = /* the page */ "Comment <b>$n</b>"

@Language("HTML")
fun g(n: String) = // the page
    "LineComment <b>$n</b>"

@Language("HTML")
val v = /* v */ "ValComment <b>x</b>"
}

// r4mark/cases2: k07_comment_more.kt
object Case246 {
fun f(n: String, c: Boolean): String {
    // language=HTML
    val a = if (c) "CIf <b>$n</b>" else "CElse <i>$n</i>"
    // language=HTML
    return "CRet <b>$n</b>"
}

// language=HTML
fun g(n: String, c: Boolean) = if (c) "CFunIf <b>$n</b>" else "CFunElse"

// language=HTML
fun h(n: String) = listOf(n).joinToString { "CLambda <b>$it</b>" }
}

// review-309/lines.kt: comment-at-end
object Case591 {
fun f(q: String?) {
    val a = q!! // sure
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: comment-ending-op
object Case592 {
fun f(q: Int) {
    val a = q // + 1
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: if-paren-in-comment
object Case601 {
fun f(a: Int) {
    val x = foo(a) // if (a)
    val t = "<b>$x</b>"
}
}

// review-309/loops.kt: for-comment-next
object Case609 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) // each
        println(n)
    println("<p>$n</p>")
}
}

// review-309/marks.kt: comment-call
object Case632 {
// language=HTML
fun respond(x: String) = call.respondText("Hey <b>$x</b>")
}

// review-309/marks.kt: comment-before-arg
object Case633 {
fun respond(x: String) = call.respondText(
    // language=HTML
    "Hey <b>$x</b>",
)
}

// review-309/marks.kt: comment-val-call
object Case634 {
fun g(x: String) {
    // language=HTML
    val body = wrap("Hey <b>$x</b>")
}
}

// review-309/marks.kt: comment-between
object Case663 {
@Language("HTML") /* a page */ fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks.kt: line-comment-between
object Case664 {
@Language("HTML")
// a page
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks2.kt: comment-mark-if
object Case679 {
fun f(ok: Boolean) {
    // language=HTML
    val s = if (ok) "Hey <b>x</b>" else ""
}
}

// r5review/cases: d30_ifs_comment_else
object Case728 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) use(x) // if only
    else
        println("<b>$x</b>")
}
}

// r5review/cases: m05_comments
object Case735 {
val name = load()
@Language("HTML") // note
val a = "Hi <b>$name</b>"
@Language("HTML") /* the page */ val b = "Hi <b>$name</b>"
@Language("HTML")
/** Doc. */
fun c() = "Hi <b>$name</b>"
// language=HTML
val d = "Hi <b>$name</b>"
}

// r7/hand: fun_after_block_comment_param_text
object Case758 {
@Language("HTML") /* x: String, */ fun page(n: Int) = "Hey <b>$n</b>"
}

// r7/hand: fun_after_comment_param_text
object Case759 {
@Language("HTML")
// x: String,
fun page(n: Int) = "Hey <b>$n</b>"
}

// r7/hand: fun_marked_comment_before_literal
object Case761 {
@Language("HTML")
fun page(n: Int) = // x: String,
    "Hey <b>$n</b>"
}

// r7/hand: fun_marked_comment_in_params
object Case762 {
@Language("HTML")
fun page(
    // x: String,
    n: Int,
) = "Hey <b>$n</b>"
}

// r7/hand: language_comment_param_after
object Case769 {
fun page(
    // language=HTML
    body: String = "Hey <b>x</b>",
) = body
}

// r7/hand: line_comment_marker_param_text
object Case770 {
// @Language("HTML") body: String,
fun page(n: Int) = "Hey <b>$n</b>"
}

// r7/hand: marked_val_typed_comment_paren
object Case771 {
@Language("HTML")
val page: String // (see below)
    get() = "Hey <b>x</b>"
}

// r7/hand: param_block_comment
object Case774 {
fun page(@Language("HTML") /* x, y) */ body: String) = "Hey <b>$body</b>"
}

// r7/hand: param_comment_then_param
object Case775 {
fun page(
    @Language("HTML") // the body, as markup
    body: String,
) = "Hey <b>$body</b>"
}

// r7/hand: try_block_comment_between
object Case779 {
fun page(n: Int): String {
    val v = try {
        load(n)
    } /* failed */
    catch (e: Exception) {
        "<b>x</b>"
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_catch_comment_before_paren
object Case781 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    catch /* any */ (e: Exception) {
        "<b>x</b>"
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_comment_own_line
object Case785 {
fun page(n: Int): String {
    val v = try {
        load(n)
    }
    // failed?
    catch (e: Exception) {
        "<b>x</b>"
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: try_comment_same_line
object Case786 {
fun page(n: Int): String {
    val v = try {
        load(n)
    } // failed?
    catch (e: Exception) {
        "<b>x</b>"
    }
    return "<p>$v</p>"
}
fun load(n: Int): String = "x"
}

// r7/hand: val_block_comment_between
object Case797 {
@Language("HTML") val /* the page */ page: String = load(1, "Hey <b>x</b>")
}

// r7/hand: val_comment_comma
object Case798 {
fun page(n: Int): String {
    @Language("HTML") val v: String = // first, the header
        "Hey <b>$n</b>"
    return v
}
}

// r7/probes: c21-comment-receiver-anon
object Case831 {
fun f(n: String) = fun(/* ( */ m: Int) = "<b>$n</b>"
val n = "<i>"
fun page() = "<p>$n</p>"
}

// r7/probes: c22-comment-receiver
object Case832 {
fun a(n: Int) = b
fun (/* ( */ String).ext(m: Int) = "<b>$m</b>"
fun page(n: String) = "<p>$n</p>"
}

// r7/probes6: h06f-open-annot-comment-fun
object Case874 {
open class P {
    open @A("(") /* c */
    // d
    fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes6: h07i-real-open-after-brace-comment
object Case885 {
open class P {
    fun a() {} open /* c */ val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m1a-brace-const-comment
object Case920 {
fun a() {} const /* c */ val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes7: m1b-typearg-const-comment
object Case931 {
@A<String> const /* c */ val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p2-lambda-infix-comment
object Case955 {
open class P {
    val a = b { } open value // c
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-enum-semi-private-comment
object Case984 {
enum class E {
    A, B; private /* c */ val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p4-semi-open-comment
object Case991 {
open class P {
    val a = 1; open /* c */ val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes9: r2-brace-infix-const-comment
object Case1035 {
val a = b { } const value // c
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes9: r4-semi-abstract-fun-comment
object Case1040 {
abstract class P {
    val a = 1; abstract /* c */ fun title(): String
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes9: r4-semi-mark-comment-fun
object Case1041 {
val a = 1; @Language("HTML") /* c */ fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes9: r4-semi-mark-open-comment-fun
object Case1043 {
val a = 1; @Language("HTML") internal /* c */ fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes9: r5-long-open-comment
object Case1046 {
open class P {
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
SELECT a F""") open /* c */ val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

