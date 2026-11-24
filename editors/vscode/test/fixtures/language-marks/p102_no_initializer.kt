import org.intellij.lang.annotations.Language

class P102A {
    @Language("HTML")
    val page: String
    val plain: String

    init {
        plain = "S01 first"
        page = "S02 <p>second</p>" // missed: #347
    }
}

class P102B {
    @Language("HTML")
    lateinit var later: String
    fun f() = "S03 <p>after lateinit</p>"
}

abstract class P102C {
    @Language("HTML")
    abstract val shell: String
    val other = "S04 <p>other</p>"
}

class P102D {
    @Language("HTML")
    val page: String

    init {
        page = "S05 <p>only</p>" // missed: #347
    }
}

class P102E {
    @Language("HTML")
    var page: String
    init {
        println("S06 log")
        page = "S07 <p>after log</p>" // missed: #347
    }
}

interface P102F {
    @Language("HTML")
    fun render(): String
    fun name() = "S08 <p>name</p>"
}

class P102G {
    @Language("HTML")
    val p: String get() = field
    val q = "S09 q"
}
