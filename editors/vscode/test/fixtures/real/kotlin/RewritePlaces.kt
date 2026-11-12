package forge.rewrite

import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

// Each place a value can land.
fun inText(v: String) = "<p>$v</p>"

fun inAttribute(v: String) = "<p title=\"$v\">x</p>"

fun inHref(v: String) = "<a href=\"$v\">x</a>"

fun inHrefPath(v: String) = "<a href=\"/quests/$v\">x</a>"

fun inScriptSrc(v: String) = "<script src=\"$v\"></script>"

fun inExpression(v: String) = "<p data-show=\"${v}\">x</p>"

fun inStyleAttribute(v: String) = "<p style=\"$v\">x</p>"

fun inStyleBody(v: String) = "<style>$v</style>"

fun inScriptBody(v: String) = "<script>$v</script>"

fun inHandler(v: String) = "<p onclick=\"$v\">x</p>"

fun inTagName(v: String) = "<div><$v title=\"a\">x</div>"

fun inAttributeName(v: String) = "<p ${v}=\"a\">x</p>"

fun unquoted(v: String) = "<p title=$v>x</p>"

// One value interpolate refuses among values it keeps: the other value says which, and where.
fun styleAmong(v: String, a: String) = "<style>$v</style><p title=\"$a\">x</p>"

fun scriptAmong(v: String, a: String) = "<script>$v</script><p title=\"$a\">x</p>"

fun handlerAmong(v: String, a: String) = "<p onclick=\"$v\" title=\"$a\">x</p>"

fun expressionAmong(v: String, a: String) = "<p data-show=\"${v}\" title=\"$a\">x</p>"

fun structureAmong(v: String, a: String) = "<p $v title=\"$a\">x</p>"
