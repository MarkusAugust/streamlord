import org.intellij.lang.annotations.Language

interface P100View<T> {
    @get:Language("HTML")
    val template: String

    @Language("HTML")
    fun render(model: T): String
}

class P100UserView : P100View<String> {
    override val template = "R83 <p>{}</p>"
    override fun render(model: String) = template.replace("R84 {}", model)
}

@Language("HTML")
fun <T : Any> p100list(xs: List<T>, f: (T) -> String): String = xs.joinToString("R85 ") { "R86 <li>${f(it)}</li>" }

@Language("HTML")
fun <T> p100where(x: T): String where T : CharSequence = "R87 <p>$x</p>"

@Language("HTML")
fun p100default(@Language("HTML") inner: String = "R88 <i>d</i>", cls: String = "R89 c"): String = "R90 <div class=\"$cls\">$inner</div>"

abstract class P100Base {
    @Language("HTML")
    protected open val shell: String = "R91 <html></html>"
}

class P100Child : P100Base() {
    override val shell = "R92 <html><body/></html>"
}
