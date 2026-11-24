import org.intellij.lang.annotations.Language
@Language("HTML") fun s1(n: String) = n + "Tail <b>x</b>"

@Language("HTML") fun s2(n: String) = "Head <b>" + n + "</b>"

fun s3(n: String): String {
    // language=HTML
    val a = n + "CTail <b>x</b>"
    return a
}

@Language("HTML") var assigned = "Start <b>x</b>"

fun s4(n: String) {
    assigned = "Assigned <i>$n</i>" // missed: #347
    assigned += "Plus <i>$n</i>" // missed: #347
}
