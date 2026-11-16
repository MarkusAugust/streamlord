import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

class WidgetContext(val rootId: String, val contentId: String, val eventsId: String)

// Each value marked where it stands: nothing to say.
fun rootHtml(k: WidgetContext, customCss: String? = null, bodyHtml: Html): Html = interpolate {
    $$"""
    <div id="$${+k.rootId}" data-theme="light" data-lt-boundary="">
        <style>$${+Trusted(customCss.orEmpty())}</style>
        <div id="$${+k.contentId}">
            $${+bodyHtml}
        </div>
        <div id="$${+k.eventsId}" hidden></div>
    </div>
    """.trimIndent()
}

fun rows(names: List<String>): Html = interpolate { """<ul>${names.joinToString("") { "<li>${+it}</li>" }}</ul>""" }

// A marked value where interpolate refuses one.
fun refusedInExpression(name: String): Html = interpolate { """<p data-text="${+name}"></p>""" }

fun refusedInStyle(css: String): Html = interpolate { """<style>${+css}</style>""" }

fun refusedForItsHost(src: String): Html = interpolate { """<script src="${+src}"></script>""" }

// A value written without the mark.
fun unmarked(title: String): Html = interpolate { """<p title="$title">${title}</p>""" }

fun unmarkedInManyDollars(title: String): Html = interpolate { $$"""<p data-text="$count">$$title</p>""" }
