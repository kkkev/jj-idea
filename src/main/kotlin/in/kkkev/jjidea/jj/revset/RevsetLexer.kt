package `in`.kkkev.jjidea.jj.revset

import com.intellij.lang.Language
import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/** Token types of [RevsetLexer]. Not tied to a PSI language: only the highlighter consumes them. */
object RevsetTokens {
    val IDENTIFIER = IElementType("REVSET_IDENTIFIER", Language.ANY)
    val FUNCTION = IElementType("REVSET_FUNCTION", Language.ANY)
    val STRING = IElementType("REVSET_STRING", Language.ANY)
    val PATTERN_KIND = IElementType("REVSET_PATTERN_KIND", Language.ANY)
    val OPERATOR = IElementType("REVSET_OPERATOR", Language.ANY)
    val LPAREN = IElementType("REVSET_LPAREN", Language.ANY)
    val RPAREN = IElementType("REVSET_RPAREN", Language.ANY)
    val COMMA = IElementType("REVSET_COMMA", Language.ANY)
    val WHITESPACE = IElementType("REVSET_WHITESPACE", Language.ANY)
    val BAD_CHARACTER = IElementType("REVSET_BAD_CHARACTER", Language.ANY)
}

private val PATTERN_KINDS = setOf("exact", "glob", "regex", "substring", "after", "before")

/** Characters of a symbol segment, per jj's `strict_identifier_part`. */
internal fun isRevsetSymbolChar(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '/'

/**
 * A hand-written lexer over jj's revset grammar (jj 0.44), for highlighting only; jj stays the sole
 * validator. Lexes the whole text in a single O(n) pass and never throws on partial input: an
 * unterminated string runs to the end of the text. Stateless (every token starts in state 0), so the
 * editor highlighter can restart at any token boundary.
 */
class RevsetLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState() = 0

    override fun getTokenType() = tokenType

    override fun getTokenStart() = tokenStart

    override fun getTokenEnd() = tokenEnd

    override fun getBufferSequence() = buffer

    override fun getBufferEnd() = endOffset

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
        } else {
            val c = buffer[tokenStart]
            tokenType = when {
                c.isWhitespace() -> run(RevsetTokens.WHITESPACE) { it.isWhitespace() }
                c == '"' || c == '\'' -> string(c)
                isRevsetSymbolChar(c) -> symbol()
                c == '@' -> at()
                c == '(' -> single(RevsetTokens.LPAREN)
                c == ')' -> single(RevsetTokens.RPAREN)
                c == ',' -> single(RevsetTokens.COMMA)
                else -> operator()
            }
        }
    }

    private fun single(type: IElementType): IElementType {
        tokenEnd = tokenStart + 1
        return type
    }

    private fun run(type: IElementType, accept: (Char) -> Boolean): IElementType {
        var i = tokenStart
        while (i < endOffset && accept(buffer[i])) i++
        tokenEnd = i
        return type
    }

    private fun string(quote: Char): IElementType {
        var i = tokenStart + 1
        while (i < endOffset && buffer[i] != quote) {
            // Only double-quoted strings have escapes; single-quoted ones are raw.
            i += if (quote == '"' && buffer[i] == '\\' && i + 1 < endOffset) 2 else 1
        }
        tokenEnd = if (i < endOffset) i + 1 else endOffset
        return RevsetTokens.STRING
    }

    /** Segments of symbol chars joined by `.`, `-` or `+` that are followed by another segment. */
    private fun symbolEnd(from: Int): Int {
        var i = from
        while (i < endOffset) {
            when {
                isRevsetSymbolChar(buffer[i]) -> i++
                buffer[i] in ".-+" && i + 1 < endOffset && isRevsetSymbolChar(buffer[i + 1]) -> i++
                else -> break
            }
        }
        return i
    }

    private fun symbol(): IElementType {
        var end = symbolEnd(tokenStart)
        val word = buffer.subSequence(tokenStart, end).toString()
        val kind = word.removeSuffix("-i")
        val isPatternKind = kind in PATTERN_KINDS &&
            end < endOffset &&
            buffer[end] == ':' &&
            !(end + 1 < endOffset && buffer[end + 1] == ':')
        return when {
            isPatternKind -> {
                tokenEnd = end + 1
                RevsetTokens.PATTERN_KIND
            }
            // `name@remote` and `workspace@` are single symbols.
            end < endOffset && buffer[end] == '@' -> {
                end = symbolEnd(end + 1)
                tokenEnd = end
                RevsetTokens.IDENTIFIER
            }
            nextNonSpace(end) == '(' -> {
                tokenEnd = end
                RevsetTokens.FUNCTION
            }
            else -> {
                tokenEnd = end
                RevsetTokens.IDENTIFIER
            }
        }
    }

    private fun at(): IElementType {
        // Standalone `@` is the working-copy commit; `@origin` is not valid jj but is kept as one token.
        tokenEnd = symbolEnd(tokenStart + 1)
        return if (tokenEnd == tokenStart + 1) RevsetTokens.OPERATOR else RevsetTokens.IDENTIFIER
    }

    private fun operator(): IElementType {
        val c = buffer[tokenStart]
        val next = if (tokenStart + 1 < endOffset) buffer[tokenStart + 1] else null
        return when {
            c == ':' && next == ':' || c == '.' && next == '.' -> {
                tokenEnd = tokenStart + 2
                RevsetTokens.OPERATOR
            }
            c in "&|~-+=" -> single(RevsetTokens.OPERATOR)
            else -> single(RevsetTokens.BAD_CHARACTER)
        }
    }

    private fun nextNonSpace(from: Int): Char? {
        var i = from
        while (i < endOffset && buffer[i].isWhitespace()) i++
        return if (i < endOffset) buffer[i] else null
    }
}
