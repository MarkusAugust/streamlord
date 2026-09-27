package io.github.markusaugust.streamlord.intellij.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import io.github.markusaugust.streamlord.analysis.NamePart
import io.github.markusaugust.streamlord.analysis.TokenKind

/**
 * The colours of what Datastar adds. Every key falls back to a standard key of the user's
 * scheme, so no theme is overridden; the plugin's own palette (dark and light) only gives the
 * Datastar concepts a face of their own, and the colour settings page lets the user change it.
 */
object DatastarColors {
    private fun key(
        name: String,
        fallback: TextAttributesKey,
    ) = TextAttributesKey.createTextAttributesKey("STREAMLORD_$name", fallback)

    val SIGNAL: TextAttributesKey = key("SIGNAL", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val SIGNAL_PATH: TextAttributesKey = key("SIGNAL_PATH", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val ACTION_BACKEND: TextAttributesKey = key("ACTION_BACKEND", DefaultLanguageHighlighterColors.KEYWORD)
    val ACTION: TextAttributesKey = key("ACTION", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val SCOPE_VARIABLE: TextAttributesKey = key("SCOPE_VARIABLE", DefaultLanguageHighlighterColors.KEYWORD)
    val OPTION_KEY: TextAttributesKey = key("OPTION_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val DURATION: TextAttributesKey = key("DURATION", DefaultLanguageHighlighterColors.NUMBER)
    val STRING: TextAttributesKey = key("STRING", DefaultLanguageHighlighterColors.STRING)
    val REGEX: TextAttributesKey = key("REGEX", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)
    val KEYWORD: TextAttributesKey = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val NUMBER: TextAttributesKey = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val KOTLIN_DOLLAR_ESCAPE: TextAttributesKey = key("KOTLIN_DOLLAR_ESCAPE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)

    val ATTRIBUTE_PREFIX: TextAttributesKey = key("ATTRIBUTE_PREFIX", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val ATTRIBUTE_PLUGIN: TextAttributesKey = key("ATTRIBUTE_PLUGIN", DefaultLanguageHighlighterColors.KEYWORD)
    val ATTRIBUTE_KEY: TextAttributesKey = key("ATTRIBUTE_KEY", DefaultLanguageHighlighterColors.CLASS_NAME)
    val MODIFIER: TextAttributesKey = key("MODIFIER", DefaultLanguageHighlighterColors.KEYWORD)
    val MODIFIER_SIGIL: TextAttributesKey = key("MODIFIER_SIGIL", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val MODIFIER_ARG: TextAttributesKey = key("MODIFIER_ARG", DefaultLanguageHighlighterColors.CLASS_NAME)

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
        }
}
