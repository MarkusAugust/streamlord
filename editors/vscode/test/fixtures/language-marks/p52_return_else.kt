import org.intellij.lang.annotations.Language

fun p52sink(s: String) = s

@Language("HTML")
fun p52a(c: Boolean): String {
    var z = "k01 a"
    z = if (c) return "k02 <p>a</p>" else "k03 b"
    return z
}

fun p52b(c: Boolean): String {
    var z = "k04 a"
    z = if (c) return "k05 a" else "k06 b"
    return z
}

@Language("HTML")
fun p52c(c: Boolean): String {
    p52sink(if (c) return "k07 <p>a</p>" else "k08 b")
    println(if (c) return "k09 <p>a</p>" else "k10 b")
    return "k11 <p>end</p>"
}

@Language("HTML")
fun p52d(c: Boolean): String {
    if (c) return "k12 <p>a</p>" else println("k13 b")
    if (c) return "k14 <p>a</p>" else "k15 b"
    if (c) return "k16 <p>a</p>"
    else return "k17 <p>b</p>"
    return "k18 <p>end</p>"
}

@Language("HTML")
fun p52e(c: Boolean, s: String?): String {
    var x = "k19 a"
    x = s ?: return "k20 <p>a</p>"
    x = when {
        c -> return "k21 <p>w</p>"
        else -> "k22 b"
    }
    val list = mutableListOf<String>()
    list += if (c) return "k23 <p>l</p>" else "k24 b"
    if (c) return "k25 <p>a</p>" else x = "k26 b"
    return x + list
}

@Language("HTML")
fun p52f(c: Boolean): String {
    val r = if (c) return "k27 <p>a</p>" else "k28 b"
    return r
}

@Language("HTML")
fun p52g(c: Boolean): String {
    if (c) {
        return "k29 <p>a</p>"
    } else "k30 b"
    return if (c) "k31 <p>x</p>" else "k32 <p>y</p>"
}

@Language("HTML")
fun p52h(c: Boolean): String {
    var z = "k33 a"
    z = if (c) "k34 b" else return "k35 <p>r</p>"
    z = (if (c) return "k36 <p>r</p>" else "k37 b")
    return z
}

@Language("HTML")
fun p52i(c: Boolean): String {
    var z = "k38 a"
    z = if (c)
        return "k39 <p>r</p>"
    else
        "k40 b"
    return z
}

@Language("HTML")
fun p52j(c: Boolean): String {
    var z = "k41 a"
    z = if (c) return "k42 <p>r</p>" else if (!c) "k43 b" else "k44 c"
    return z
}

@Language("HTML")
fun p52k(n: Int): String {
    var z = "k45 a"
    z = when (n) {
        1 -> return "k46 <p>1</p>"
        2 -> "k47 two"
        else -> "k48 else"
    }
    return z
}

@Language("HTML")
fun p52l(c: Boolean): String {
    var z: String
    z = try { return "k49 <p>t</p>" } catch (e: Exception) { "k50 x" }
    return z
}

@Language("HTML")
fun p52m(c: Boolean): String {
    val m = mutableMapOf<String, String>()
    m["k51 key"] = if (c) return "k52 <p>m</p>" else "k53 v"
    return m.toString()
}
