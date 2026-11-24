import org.intellij.lang.annotations.Language

interface P95R { fun render(): String }

val p95obj = object : P95R {
    @Language("HTML")
    override fun render(): String = "R19 <p>obj</p>"
    fun other() = "R20 <p>plain</p>"
}

@Language("HTML")
fun p95make(): P95R = object : P95R {
    override fun render() = "R21 <p>inner</p>"
}

class P95Init {
    @Language("HTML")
    val page: String
    val plain: String

    init {
        page = "R22 <p>init</p>" // missed: #347
        plain = "R23 <p>plain</p>"
    }

    @Language("HTML")
    lateinit var later: String
}

@Language("HTML")
operator fun String.unaryPlus(): String = "R24 <b>$this</b>"

@Language("HTML")
infix fun String.wrap(tag: String): String = "R25 <$tag>$this</$tag>"

fun p95use() = "R26 x" wrap "R27 p"
