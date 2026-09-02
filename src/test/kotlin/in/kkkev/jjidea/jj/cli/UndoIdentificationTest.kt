package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [findTaggedOperation] — the per-invocation-token matching at the heart of undo
 * identification (docs/design/undo-support-roadmap.md). A fresh UUID token is injected as
 * `--config jj-idea.undo-token=<token>`, so the operation this invocation wrote is the one whose
 * recorded argv contains it - unlike a parent-linkage approach, this is exact regardless of what
 * else lands in the op log concurrently (another IDE window, an agent, a terminal `jj` command).
 */
class UndoIdentificationTest {
    private fun entry(
        id: String,
        args: String,
        isSnapshot: Boolean = false
    ) = OperationEntry(
        id = OperationId(id),
        parents = emptyList(),
        description = "",
        time = "",
        user = "",
        isSnapshot = isSnapshot,
        isRoot = false,
        args = args
    )

    @Test
    fun `direct non-snapshot match is found`() {
        val entries = listOf(entry("op1", "args: jj --config jj-idea.undo-token=TOK abandon -r @"))

        findTaggedOperation(entries, "TOK") shouldBe TaggedOperationMatch.Found(OperationId("op1"))
    }

    @Test
    fun `snapshot-then-real op - the case parent-linkage got wrong - picks the real op`() {
        // jj snapshots a dirty working copy as its own operation before the real command; that
        // paired snapshot carries the identical argv, since it's the same invocation.
        val entries = listOf(
            entry("snap", "args: jj --config jj-idea.undo-token=TOK abandon -r @", isSnapshot = true),
            entry("real", "args: jj --config jj-idea.undo-token=TOK abandon -r @", isSnapshot = false)
        )

        findTaggedOperation(entries, "TOK") shouldBe TaggedOperationMatch.Found(OperationId("real"))
    }

    @Test
    fun `interleaving - a concurrent op with no token is never matched`() {
        val entries = listOf(
            entry("agent-op", "args: jj describe -m unrelated"),
            entry("ours", "args: jj --config jj-idea.undo-token=TOK abandon -r @")
        )

        findTaggedOperation(entries, "TOK") shouldBe TaggedOperationMatch.Found(OperationId("ours"))
    }

    @Test
    fun `zero matches - the command was a no-op that wrote nothing`() {
        val entries = listOf(entry("op1", "args: jj describe -m unrelated"))

        findTaggedOperation(entries, "TOK") shouldBe TaggedOperationMatch.NotFound
    }

    @Test
    fun `empty window - zero matches, not a crash`() {
        findTaggedOperation(emptyList(), "TOK") shouldBe TaggedOperationMatch.NotFound
    }

    @Test
    fun `two non-snapshot ops both carrying the token - withhold rather than guess`() {
        val entries = listOf(
            entry("op1", "args: jj --config jj-idea.undo-token=TOK abandon -r @"),
            entry("op2", "args: jj --config jj-idea.undo-token=TOK abandon -r @")
        )

        findTaggedOperation(entries, "TOK") shouldBe TaggedOperationMatch.Ambiguous
    }

    @Test
    fun `a match is never a snapshot op, even if it is the only carrier of the token`() {
        val entries =
            listOf(entry("snap-only", "args: jj --config jj-idea.undo-token=TOK abandon -r @", isSnapshot = true))

        findTaggedOperation(entries, "TOK") shouldBe TaggedOperationMatch.NotFound
    }

    @Test
    fun `a different token on the same command never matches`() {
        val entries = listOf(
            entry(
                "op1",
                "args: jj --config jj-idea.undo-token=550e8400-e29b-41d4-a716-446655440000 abandon -r @"
            )
        )

        findTaggedOperation(entries, "6ba7b810-9dad-11d1-80b4-00c04fd430c8") shouldBe TaggedOperationMatch.NotFound
    }
}
