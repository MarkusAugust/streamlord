/**
 * The mark, read from the VS Code extension so there is one of it.
 *
 * The crown is the Iron Crown of Kell from the grimoire's own story, and the gradient over
 * the three streams is `#4fc1ff` to `#c586c0`, the signal blue and the action purple the
 * extension recommends for `$signal` and `@get`. The logo, the editors and the lore already
 * agree; nothing here should be allowed to drift from that file.
 */
import icon from "../../editors/vscode/media/icon.svg?raw"

/** The mark, with its fixed pixel size removed so CSS can size it. */
export const MARK = icon.replace(/\s(width|height)="256"/g, "")

/** The same file as a favicon. A data URI, so there is no second copy in public/. */
export const FAVICON = `data:image/svg+xml,${encodeURIComponent(icon)}`
