package io.github.markusaugust.streamlord.analysis

/*
 * The places a file names a signal, for go to definition, find references and rename.
 */

/**
 * One place: a part of a read's path, `name` in `$user.name` for `user.name`, or the name a
 * defining place writes, with [declaration] saying how a new one is written there.
 */
public data class SignalSite(
    val start: Int,
    val end: Int,
    val signal: String,
    val declaration: SignalDeclaration? = null,
) {
    /**
     * Does the site name [name], or, with [under], define a signal under it, which a rename moves with it?
     * A key inside an object is written without the object's name, so a rename of the object leaves it as it is.
     */
    public fun names(
        name: String,
        under: Boolean = false,
    ): Boolean =
        signal == name ||
            (under && declaration != null && declaration.kind != SignalDeclaration.Kind.OBJECT_KEY && signal.startsWith("$name."))

    /**
     * Why the signal cannot be renamed from here, or null. A read of a name with a hyphen is not
     * found, so a rename would leave them stale; a property of a signals class is Kotlin's.
     */
    public fun renameRefusal(): String? =
        when {
            '-' in signal -> "A rename does not find the reads of '$signal', whose hyphen no \$name reaches."
            declaration?.kind != SignalDeclaration.Kind.PROPERTY -> null
            else -> "'$signal' is a property of a signals class: rename it with Kotlin's rename."
        }

    /**
     * What goes over the site in [text] when the signal [old] is renamed [newName], or null when
     * nothing written there can name it. A key inside the object renamed is written without the
     * object's name and stays as it is; any other place that defines a signal under [old] moves with it.
     */
    public fun renameText(
        text: String,
        old: String,
        newName: String,
    ): String? {
        val d = declaration ?: return signalReadRenameText(signal, newName)
        return when {
            d.name == old -> signalRenameText(text, d, newName)
            d.kind == SignalDeclaration.Kind.OBJECT_KEY -> text.substring(d.start, d.end)
            else -> signalRenameText(text, d, newName + d.name.substring(old.length))
        }
    }
}

/**
 * The sites of a file: each part of a read whose path up to it is in [defined], so that
 * `$user.name.length` reads `user` in its first part and `user.name` in its second, and every
 * defining place. A read that a defining place covers, `$count = 1`, which assigns the signal, is
 * that place alone, so that a rename writes it once.
 */
public fun signalSites(
    reads: List<SignalReference>,
    declarations: List<SignalDeclaration>,
    defined: Set<String>,
): List<SignalSite> {
    val out = ArrayList<SignalSite>()
    for (read in reads) {
        var at = read.end - read.name.length
        if (declarations.any { it.start <= at && read.end <= it.end }) continue
        var path = ""
        for (part in read.name.split('.')) {
            path = if (path.isEmpty()) part else "$path.$part"
            if (path in defined) out += SignalSite(at, at + part.length, path)
            at += part.length + 1
        }
    }
    for (d in declarations) out += SignalSite(d.start, d.end, d.name, d)
    return out
}

/** The site at [offset], its end included, as a caret just after a name stands on it. */
public fun siteAt(
    sites: List<SignalSite>,
    offset: Int,
): SignalSite? = sites.firstOrNull { it.start <= offset && offset <= it.end }
