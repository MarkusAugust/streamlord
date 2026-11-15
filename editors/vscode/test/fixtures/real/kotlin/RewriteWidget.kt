package forge.rewrite

import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

class WidgetContext(val rootId: String, val contentId: String, val eventsId: String)

// A trim call after the literal, a value in the body of a <style>, and four values interpolate keeps.
fun rootHtml(k: WidgetContext, customCss: String? = null, bodyHtml: String): String =
    """
    <div id="${k.rootId}" data-theme="light" data-lt-boundary="">
        <style>${customCss.orEmpty()}</style>
        <div id="${k.contentId}">
            $bodyHtml
        </div>
        <div id="${k.eventsId}" hidden></div>
    </div>
    """.trimIndent()
