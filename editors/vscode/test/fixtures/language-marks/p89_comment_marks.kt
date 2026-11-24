fun p89a(name: String): String {
    // language=HTML
    val greeting =
        "Q105 <h1>Hello, $name</h1>"
    return greeting
}

fun p89b(): String {
    // language=HTML
    // more about this
    val x = "Q106 <p>after two comments</p>"
    return x
}

fun p89c(): String {
    /* language=HTML */
    val y = "Q107 <p>block comment</p>"
    return y
}

fun p89d(): String {
    // language=HTML

    val z = "Q108 <p>after blank</p>"
    return z
}

fun p89e(): String {
    // language=HTML
    return "Q109 <p>" + "P890 middle" + "P891 </p>"
}

fun p89f(): String {
    val a = "P892 a" // language=HTML
    val b = "P893 <b>b</b>"
    return a + b
}

class P89Holder {
    // language=HTML
    val prop = "P894 <em>prop</em>"

    // language=HTML
    fun method() = "P895 <em>method</em>"
}

fun p89g(): String {
    // language = HTML
    val spaced = "P896 <p>spaced</p>"
    return spaced
}

fun p89h(): String {
    //language=HTML
    val tight = "P897 <p>tight</p>"
    return tight
}

fun p89i(): String {
    // language=HTML prefix=<div> suffix=</div>
    val pre = "P898 <p>prefix</p>"
    return pre
}
