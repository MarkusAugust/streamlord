package io.github.markusaugust.streamlord.intellij.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import io.github.markusaugust.streamlord.analysis.NamePart
import io.github.markusaugust.streamlord.analysis.TokenKind

/**
 * The colours of Datastar. The plugin ships no colours of its own: every key falls back to a
 * standard key, so a token takes whatever the scheme gives that key, and a colour set for Default or
 * Darcula would win over the theme in every scheme built on them. Each fallback is the standard key
 * of the same kind of thing, the one the VS Code grammar's scope names, where IntelliJ's schemes
 * colour it: a signal as a static field, a property or key as an instance field, a global as
 * JavaScript colours one, a modifier's argument as a constant. Where they leave that key plain, it is
 * the nearest key they colour: an action, written with an `@`, as a keyword, as before 0.22. The
 * parts of a Datastar attribute's name are coloured as HTML's attribute names, which every scheme
 * sets apart from text, where the markup attribute key is the text's own in Darcula. Classic Default,
 * Darcula and the New UI's Light, Dark and Islands Dark colour every key but an identifier, a call
 * and an operator, which they leave plain in JavaScript too; HighlightingTest holds them to that.
 * The colour settings page lets the user give a token a face of its own.
 */
object DatastarColors {
    private fun key(
        name: String,
        fallback: TextAttributesKey,
    ) = TextAttributesKey.createTextAttributesKey("STREAMLORD_$name", fallback)

    /**
     * HTML's attribute name, XmlHighlighterColors.HTML_ATTRIBUTE_NAME, found by its name: a key is
     * one by its name, and XmlHighlighterColors gives it its fallback when the XML module loads it,
     * before any HTML is painted. The plugin verifier cannot see the XML module of 2026.3, where the
     * class is there at run time, and reports a reference to it as a missing class.
     */
    private val HTML_ATTRIBUTE_NAME = TextAttributesKey.createTextAttributesKey("HTML_ATTRIBUTE_NAME")
    val SIGNAL: TextAttributesKey = key("SIGNAL", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val SIGNAL_PATH: TextAttributesKey = key("SIGNAL_PATH", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val ACTION_BACKEND: TextAttributesKey = key("ACTION_BACKEND", DefaultLanguageHighlighterColors.KEYWORD)
    val ACTION: TextAttributesKey = key("ACTION", DefaultLanguageHighlighterColors.KEYWORD)
    val SCOPE_VARIABLE: TextAttributesKey = key("SCOPE_VARIABLE", DefaultLanguageHighlighterColors.KEYWORD)
    val OPTION_KEY: TextAttributesKey = key("OPTION_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val OBJECT_KEY: TextAttributesKey = key("OBJECT_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val PROPERTY: TextAttributesKey = key("PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val METHOD: TextAttributesKey = key("METHOD", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val GLOBAL: TextAttributesKey = key("GLOBAL", DefaultLanguageHighlighterColors.CONSTANT)
    val IDENTIFIER: TextAttributesKey = key("IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    val OPERATOR: TextAttributesKey = key("OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val COMMENT: TextAttributesKey = key("COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    val TEMPLATE_PUNCTUATION: TextAttributesKey = key("TEMPLATE_PUNCTUATION", DefaultLanguageHighlighterColors.KEYWORD)
    val DURATION: TextAttributesKey = key("DURATION", DefaultLanguageHighlighterColors.NUMBER)
    val STRING: TextAttributesKey = key("STRING", DefaultLanguageHighlighterColors.STRING)
    val REGEX: TextAttributesKey = key("REGEX", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)
    val KEYWORD: TextAttributesKey = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val NUMBER: TextAttributesKey = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val KOTLIN_DOLLAR_ESCAPE: TextAttributesKey = key("KOTLIN_DOLLAR_ESCAPE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)

    val ATTRIBUTE_PREFIX: TextAttributesKey = key("ATTRIBUTE_PREFIX", HTML_ATTRIBUTE_NAME)
    val ATTRIBUTE_PLUGIN: TextAttributesKey = key("ATTRIBUTE_PLUGIN", HTML_ATTRIBUTE_NAME)
    val ATTRIBUTE_KEY: TextAttributesKey = key("ATTRIBUTE_KEY", HTML_ATTRIBUTE_NAME)
    val MODIFIER: TextAttributesKey = key("MODIFIER", DefaultLanguageHighlighterColors.KEYWORD)
    val MODIFIER_SIGIL: TextAttributesKey = key("MODIFIER_SIGIL", HTML_ATTRIBUTE_NAME)
    val MODIFIER_ARG: TextAttributesKey = key("MODIFIER_ARG", DefaultLanguageHighlighterColors.CONSTANT)

    val ALL: List<TextAttributesKey> =
        listOf(
            SIGNAL,
            SIGNAL_PATH,
            ACTION_BACKEND,
            ACTION,
            SCOPE_VARIABLE,
            OPTION_KEY,
            OBJECT_KEY,
            PROPERTY,
            METHOD,
            GLOBAL,
            IDENTIFIER,
            OPERATOR,
            COMMENT,
            TEMPLATE_PUNCTUATION,
            DURATION,
            STRING,
            REGEX,
            KEYWORD,
            NUMBER,
            KOTLIN_DOLLAR_ESCAPE,
            ATTRIBUTE_PREFIX,
            ATTRIBUTE_PLUGIN,
            ATTRIBUTE_KEY,
            MODIFIER,
            MODIFIER_SIGIL,
            MODIFIER_ARG,
        )

    fun of(kind: TokenKind): TextAttributesKey =
        when (kind) {
            TokenKind.SIGNAL -> SIGNAL
            TokenKind.SIGNAL_PATH -> SIGNAL_PATH
            TokenKind.ACTION_BACKEND -> ACTION_BACKEND
            TokenKind.ACTION -> ACTION
            TokenKind.SCOPE_VARIABLE -> SCOPE_VARIABLE
            TokenKind.OPTION_KEY -> OPTION_KEY
            TokenKind.DURATION -> DURATION
            TokenKind.STRING -> STRING
            TokenKind.REGEX -> REGEX
            TokenKind.KEYWORD -> KEYWORD
            TokenKind.NUMBER -> NUMBER
            TokenKind.KOTLIN_DOLLAR_ESCAPE -> KOTLIN_DOLLAR_ESCAPE
            TokenKind.OBJECT_KEY -> OBJECT_KEY
            TokenKind.PROPERTY -> PROPERTY
            TokenKind.METHOD -> METHOD
            TokenKind.GLOBAL -> GLOBAL
            TokenKind.IDENTIFIER -> IDENTIFIER
            TokenKind.OPERATOR -> OPERATOR
            TokenKind.COMMENT -> COMMENT
            TokenKind.TEMPLATE_PUNCTUATION -> TEMPLATE_PUNCTUATION
        }

    fun of(part: NamePart): TextAttributesKey =
        when (part) {
            NamePart.PREFIX -> ATTRIBUTE_PREFIX
            NamePart.PLUGIN -> ATTRIBUTE_PLUGIN
            NamePart.KEY_SEPARATOR -> MODIFIER_SIGIL
            NamePart.KEY -> ATTRIBUTE_KEY
            NamePart.MODIFIER_SIGIL -> MODIFIER_SIGIL
            NamePart.MODIFIER -> MODIFIER
            NamePart.MODIFIER_ARG_DOT -> MODIFIER_SIGIL
            NamePart.MODIFIER_ARG -> MODIFIER_ARG
            NamePart.MODIFIER_ARG_NUMBER -> NUMBER
            NamePart.MODIFIER_ARG_DURATION -> DURATION
        }
}
