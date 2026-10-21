package `in`.kkkev.jjidea.jj.revset

import com.intellij.psi.tree.IElementType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RevsetLexerTest {
    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = RevsetLexer()
        lexer.start(text)
        return buildList {
            while (lexer.tokenType != null) {
                add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
        }
    }

    private fun significant(text: String) = lex(text).filter { it.first != RevsetTokens.WHITESPACE }

    @Test
    fun `function call with depth and trailing range operator`() {
        significant("ancestors(@, 20)::") shouldContainExactly listOf(
            RevsetTokens.FUNCTION to "ancestors",
            RevsetTokens.LPAREN to "(",
            RevsetTokens.OPERATOR to "@",
            RevsetTokens.COMMA to ",",
            RevsetTokens.IDENTIFIER to "20",
            RevsetTokens.RPAREN to ")",
            RevsetTokens.OPERATOR to "::"
        )
    }

    @Test
    fun `remote bookmark is one symbol`() {
        significant("x@origin") shouldContainExactly listOf(RevsetTokens.IDENTIFIER to "x@origin")
    }

    @Test
    fun `workspace working copy is one symbol`() {
        significant("default@") shouldContainExactly listOf(RevsetTokens.IDENTIFIER to "default@")
    }

    @Test
    fun `pattern kind and escaped string`() {
        significant("""description(exact:"a \"b\"")""") shouldContainExactly listOf(
            RevsetTokens.FUNCTION to "description",
            RevsetTokens.LPAREN to "(",
            RevsetTokens.PATTERN_KIND to "exact:",
            RevsetTokens.STRING to """"a \"b\""""",
            RevsetTokens.RPAREN to ")"
        )
    }

    @Test
    fun `case-insensitive pattern kind`() {
        significant("""glob-i:"x*"""").map { it.first } shouldContainExactly
            listOf(RevsetTokens.PATTERN_KIND, RevsetTokens.STRING)
    }

    @Test
    fun `negation and set operators`() {
        significant("~empty() & mine()").map { it.second } shouldContainExactly
            listOf("~", "empty", "(", ")", "&", "mine", "(", ")")
    }

    @Test
    fun `hyphen inside a symbol joins, trailing hyphen is the parents operator`() {
        significant("my-branch-") shouldContainExactly listOf(
            RevsetTokens.IDENTIFIER to "my-branch",
            RevsetTokens.OPERATOR to "-"
        )
    }

    @Test
    fun `double dot is a single operator`() {
        significant("a..b") shouldContainExactly listOf(
            RevsetTokens.IDENTIFIER to "a",
            RevsetTokens.OPERATOR to "..",
            RevsetTokens.IDENTIFIER to "b"
        )
    }

    @Test
    fun `unterminated string runs to the end`() {
        lex("""author("abc""") shouldContainExactly listOf(
            RevsetTokens.FUNCTION to "author",
            RevsetTokens.LPAREN to "(",
            RevsetTokens.STRING to """"abc"""
        )
    }

    @Test
    fun `single quoted string is raw`() {
        significant("""'a\'""") shouldContainExactly listOf(RevsetTokens.STRING to """'a\'""")
    }

    @Test
    fun `unknown character is bad`() {
        significant("a # b").map { it.first } shouldContainExactly
            listOf(RevsetTokens.IDENTIFIER, RevsetTokens.BAD_CHARACTER, RevsetTokens.IDENTIFIER)
    }

    @Test
    fun `function name before whitespace and paren is still a function`() {
        significant("heads (x)").first() shouldBe (RevsetTokens.FUNCTION to "heads")
    }

    @Test
    fun `tokens are contiguous and cover random input without throwing`() {
        val alphabet = "ab1_-+./@:&|~()\", \n'\\é#="
        val random = Random(42)
        repeat(300) {
            val text = String(CharArray(random.nextInt(0, 40)) { alphabet[random.nextInt(alphabet.length)] })
            val lexer = RevsetLexer()
            lexer.start(text)
            var expectedStart = 0
            while (lexer.tokenType != null) {
                lexer.tokenStart shouldBe expectedStart
                (lexer.tokenEnd > lexer.tokenStart) shouldBe true
                expectedStart = lexer.tokenEnd
                lexer.advance()
            }
            expectedStart shouldBe text.length
        }
    }
}
