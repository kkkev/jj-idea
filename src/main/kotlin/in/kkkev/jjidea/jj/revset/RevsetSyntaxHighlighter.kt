package `in`.kkkev.jjidea.jj.revset

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * Maps [RevsetLexer] tokens onto the platform's default language colours, so every theme applies
 * without a ColorSettingsPage.
 */
class RevsetSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = RevsetLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(
        when (tokenType) {
            RevsetTokens.FUNCTION -> DefaultLanguageHighlighterColors.FUNCTION_CALL
            RevsetTokens.STRING -> DefaultLanguageHighlighterColors.STRING
            RevsetTokens.PATTERN_KIND -> DefaultLanguageHighlighterColors.KEYWORD
            RevsetTokens.OPERATOR -> DefaultLanguageHighlighterColors.OPERATION_SIGN
            RevsetTokens.LPAREN, RevsetTokens.RPAREN -> DefaultLanguageHighlighterColors.PARENTHESES
            RevsetTokens.COMMA -> DefaultLanguageHighlighterColors.COMMA
            RevsetTokens.IDENTIFIER -> DefaultLanguageHighlighterColors.IDENTIFIER
            RevsetTokens.BAD_CHARACTER -> HighlighterColors.BAD_CHARACTER
            else -> null
        }
    )
}
