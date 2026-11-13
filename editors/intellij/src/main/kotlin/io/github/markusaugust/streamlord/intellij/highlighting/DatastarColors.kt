package io.github.markusaugust.streamlord.intellij.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.XmlHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import io.github.markusaugust.streamlord.analysis.NamePart
import io.github.markusaugust.streamlord.analysis.TokenKind

/**
 * The colours of Datastar. Every key falls back to the standard key of the same kind of thing, the
 * one the VS Code grammar's scope names: a signal is coloured as a static field, an action as a
 * function declaration, a modifier as a keyword, a modifier's argument as a constant, and each part
 * of an attribute's name as an HTML attribute name. The plugin ships no colours of its own, so a
 * token takes whatever the theme gives that standard key; a colour set for Default or Darcula would
 * win over the theme in every scheme built on them, which is how themes are built. Default and
 * Darcula colour every key but an identifier, a call and an operator, which they leave plain in
 * JavaScript too. The colour settings page lets the user give a token a face of its own.
 */
object DatastarColors {
    private fun key(
        name: String,
        fallback: TextAttributesKey,
    ) = TextAttributesKey.createTextAttributesKey("STREAMLORD_$name", fallback)

    val SIGNAL: TextAttributesKey = key("SIGNAL", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val SIGNAL_PATH: TextAttributesKey = key("SIGNAL_PATH", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val ACTION_BACKEND: TextAttributesKey = key("ACTION_BACKEND", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val ACTION: TextAttributesKey = key("ACTION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val SCOPE_VARIABLE: TextAttributesKey = key("SCOPE_VARIABLE", DefaultLanguageHighlighterColors.KEYWORD)
    val OPTION_KEY: TextAttributesKey = key("OPTION_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val OBJECT_KEY: TextAttributesKey = key("OBJECT_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val PROPERTY: TextAttributesKey = key("PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val METHOD: TextAttributesKey = key("METHOD", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val GLOBAL: TextAttributesKey = key("GLOBAL", DefaultLanguageHighlighterColors.GLOBAL_VARIABLE)
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

    val ATTRIBUTE_PREFIX: TextAttributesKey = key("ATTRIBUTE_PREFIX", XmlHighlighterColors.HTML_ATTRIBUTE_NAME)
    val ATTRIBUTE_PLUGIN: TextAttributesKey = key("ATTRIBUTE_PLUGIN", XmlHighlighterColors.HTML_ATTRIBUTE_NAME)
    val ATTRIBUTE_KEY: TextAttributesKey = key("ATTRIBUTE_KEY", XmlHighlighterColors.HTML_ATTRIBUTE_NAME)
    val MODIFIER: TextAttributesKey = key("MODIFIER", DefaultLanguageHighlighterColors.KEYWORD)
    val MODIFIER_SIGIL: TextAttributesKey = key("MODIFIER_SIGIL", XmlHighlighterColors.HTML_ATTRIBUTE_NAME)
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
