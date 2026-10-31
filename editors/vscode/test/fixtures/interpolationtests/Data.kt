package interpolationtests

/** The values the three files interpolate, so every file shows the same forms. */
data class Applicant(val name: String, val email: String, val errors: List<String>)

val applicant = Applicant(
    name = "Alex Doe",
    email = "alex@",
    errors = listOf("Enter an email address with an @", "Enter a date that exists"),
)

const val variant = "secondary"
const val fieldId = "email"
const val tag = "section"
val count = applicant.errors.size

fun buttonAttributes(): String = """class="lt-button" data-variant="$variant""""
