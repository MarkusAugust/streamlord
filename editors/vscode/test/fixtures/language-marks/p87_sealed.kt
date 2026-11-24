import org.intellij.lang.annotations.Language

sealed interface P87Msg {
    data class Info(val text: String) : P87Msg
    data class Error(val code: Int) : P87Msg
    object Empty : P87Msg
}

@Language("HTML")
fun p87render(m: P87Msg): String = when (m) {
    is P87Msg.Info -> "Q81 <p class=\"info\">${m.text}</p>"
    is P87Msg.Error -> {
        val label = "Q82 code ${m.code}"
        "Q83 <p class=\"err\">$label</p>"
    }
    P87Msg.Empty -> "Q84 "
}

fun p87plain(m: P87Msg): String = when (m) {
    is P87Msg.Info -> "Q85 <p>${m.text}</p>"
    else -> "Q86 other"
}

@Language("HTML")
fun p87block(m: P87Msg): String {
    return when (m) {
        is P87Msg.Info -> if (m.text.isBlank()) "Q87 blank" else "Q88 <p>${m.text}</p>"
        is P87Msg.Error -> "Q89 <b>${m.code}</b>".also { println("Q90 logged") }
        P87Msg.Empty -> run { "Q91 <i>empty</i>" }
    }
}

sealed class P87View {
    @Language("HTML")
    abstract fun html(): String

    class A : P87View() {
        override fun html() = "Q92 <a></a>"
    }

    class B : P87View() {
        @Language("HTML")
        override fun html(): String = "Q93 <b></b>"
    }
}
