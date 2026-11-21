// r7/probes: c14b-mark-args3
object Case820 {
@Language("HTML") @A<B<C<D>>> val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes: c14c-mark-space-args
object Case821 {
@Language("HTML") @A <String> val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes: c14d-mark-newline-args
object Case822 {
@Language("HTML") @A<
    String> val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes: c14e-args3-fun
object Case823 {
open class P {
    @A<B<C<D>>> open fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes: c14f-mark-args3-fun
object Case824 {
@Language("HTML") @A<B<C<D>>> fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes: c15-args2-ok
object Case825 {
open class P {
    @A<B<C>> open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c16-args-star
object Case826 {
open class P {
    @A<*> open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c18-getter-after-args3
object Case828 {
class P {
    val title = "<b>x</b>"
        @A<B<C<D>>> get() = field
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c19-getter-after-dotted
object Case829 {
class P {
    val title = "<b>x</b>"
        @a.`b` get() = field
    fun page() = "<p>$title</p>"
}
}

// r7/probes: c20-for-annot-braces
object Case830 {
fun outer(n: String) {
    for (n in @A rows) {
        println("<p>$n</p>")
    }
    println("<p>$n</p>")
}
}

// r7/probes: c23-statement-const
object Case833 {
fun f() {
    a const val t = "<b>"
}
}

// r7/probes: e01-plus-const
object Case834 {
val a = "a" +
    const
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: e02-plus-open
object Case835 {
open class P {
    val a = b +
        open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e03-and-private
object Case836 {
val a = b &&
    private
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: e04-if-newline-const-member
object Case837 {
class P {
    val a = if (c)
        const
    else shut
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e04b-if-newline-open-statement
object Case838 {
open class P {
    init {
        if (c)
            open
    }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e05-elvis-open
object Case839 {
open class P {
    val a = b ?:
        open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e06-as-open
object Case840 {
open class P {
    val a = b as
        open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e07-arrow-open
object Case841 {
open class P {
    val a = when (b) {
        1 ->
            open
        else -> shut
    }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e08-statement-const
object Case842 {
fun f(const: Int) {
    const
    val title = "<b>x</b>"
    println("<p>$title</p>")
}
}

// r7/probes: e09-in-open
object Case843 {
open class P {
    val a = b in
        open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e10-paren-newline-open
object Case844 {
open class P {
    val a = load(
        open
    )
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e11-lambda-open
object Case845 {
open class P {
    val a = lazy {
        open
    }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e12-lambda-const
object Case846 {
val a = lazy {
    const
}
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: e13-comparison-open
object Case847 {
open class P {
    val a = b <
        open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes: e14-else-newline-const
object Case848 {
val a = if (c) x else
    const
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes: e15-return-const
object Case849 {
fun f(const: Int): Int {
    return 1 +
        const
}
val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes6: h02b-super-label-open
object Case855 {
open class C : A, B {
    fun g(x: Int) = super<A>@C.f(x)
    open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h04b-fun-get-private
object Case860 {
class P {
    val title = "<b>x</b>"
    private fun get() = title
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h04c-getter
object Case861 {
class P {
    val title = "<b>x</b>"
        get() = field
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h04d-getter-private
object Case862 {
class P {
    val title = "<b>x</b>"
        private get() = field
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h05-private-fun-interface
object Case865 {
private fun interface Q {
    fun run()
    val title: String get() = "<b>x</b>"
}
fun page() = "<p>$title</p>"
}

// r7/probes6: h05c-private-fun-interface-in-class
object Case867 {
class P {
    private fun interface Q {
        fun run()
        val title: String get() = "<b>x</b>"
    }
}
}

// r7/probes6: h05d-private-interface
object Case868 {
private interface Q {
    fun run()
    val title: String get() = "<b>x</b>"
}
fun page() = "<p>$title</p>"
}

// r7/probes6: h06-open-fun
object Case869 {
open class P {
    open fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes6: h06b-private-fun
object Case870 {
class P {
    private fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes6: h06c-override-fun
object Case871 {
open class P : Q() {
    override fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes6: h06d-mark-fun
object Case872 {
@Language("HTML")
fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes6: h06e-mark-annot-fun
object Case873 {
@Language("HTML") @A(1) /* c */
// d
private fun title(): String = load()
fun page() = "<p>${title()}</p>"
}

// r7/probes6: h06g-fun-after-brace-open
object Case875 {
open class P {
    fun a() {} open fun title() = "<b>x</b>"
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes6: h06h-fun-after-brace-mark
object Case876 {
class P {
    fun a() {} @Language("HTML") fun title(): String = load()
    fun page() = "<p>${title()}</p>"
}
}

// r7/probes6: h07g-real-open-after-value
object Case883 {
open class P {
    val a = "<i>" open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h07h-real-open-after-brace-annot-brackets
object Case884 {
open class P {
    fun a() {} open @A("(") val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h07j-real-open-after-brace-newline
object Case886 {
open class P {
    fun a() {} open
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h07k-real-open-after-brace-group
object Case887 {
open class P {
    fun a() {} open @[A B] val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h07l-real-private-after-brace-typeargs
object Case888 {
fun a() {} private @A<B, C> val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes6: h07m-open-before-typearg-annotation
object Case889 {
open class P {
    open @A<String> val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h07n-mark-before-typearg-annotation
object Case890 {
@Language("HTML") @A<String> val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes6: h07r-enum-entry-const
object Case894 {
enum class E { const; val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h08-abstract-then-open
object Case898 {
abstract class P {
    abstract fun f(): List<String> open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h10-typearg-newline
object Case899 {
@A<
    String> private val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes6: h10b-typearg-newline-const
object Case900 {
@A<
    String> const val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes6: h10d-typearg-newline-mark-after
object Case901 {
@A<
    String> @Language("HTML") val title = load()
fun page() = "<p>$title</p>"
}

// r7/probes6: h10e-typearg-newline-open
object Case902 {
open class P {
    @A<
        String> open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h11-typearg-space
object Case903 {
@A <String> private val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes6: h11b-typearg3-private
object Case904 {
@A<B<C<D>>> private val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes6: h11c-typearg3-const
object Case905 {
@A<B<C<D>>> const val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes6: h11d-typearg3-open
object Case906 {
open class P {
    @A<B<C<D>>> open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h11e-funtype-open
object Case907 {
open class P {
    @A<(Int) -> Unit> open val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h11h-typearg-const-comma
object Case908 {
@A<B, C> const val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

