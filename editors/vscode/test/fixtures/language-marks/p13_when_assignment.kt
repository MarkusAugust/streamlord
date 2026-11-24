import org.intellij.lang.annotations.Language

@Language("HTML")
var p13page = ""

fun p13a(x: Int) {
    when (x) {
        1 -> p13page = "W1 <b>x</b>" // missed: #347
        else -> p13page = "W2 <b>x</b>" // missed: #347
    }
    when {
        x > 2 -> p13page += "W3 <b>x</b>" // missed: #347
    }
}
