package `in`.kkkev.jjidea.jj.conflict

/**
 * Shared conflict-marker fixtures for [JjConflictBlockParserTest] and
 * [ExtractorBlockParserEquivalenceTest]. Deliberately independent of
 * [JjMarkerConflictExtractorTest]'s own inline fixtures - that file is the pre-refactor
 * regression contract for [JjMarkerConflictExtractor] (it must keep passing byte-for-byte
 * unmodified), so its fixtures are left in place rather than migrated onto this list.
 */
object ConflictMarkerFixtures {
    val gitWithBase = """
        |context before
        |<<<<<<< abc "side A"
        |ours content
        |||||||| base "parent"
        |base content
        |=======
        |theirs content
        |>>>>>>> def "side B"
        |context after
    """.trimMargin()

    val gitEmptyBase = """
        |<<<<<<< abc "side A" (rebase destination)
        |ours content
        |||||||| parent "base"
        |=======
        |theirs content
        |>>>>>>> def "side B"
    """.trimMargin()

    val snapshot = """
        |context before
        |<<<<<<< Conflict 1 of 1
        |+++++++ Contents of side #1
        |ours content
        |------- Base
        |base content
        |+++++++ Contents of side #2
        |theirs content
        |>>>>>>> Conflict 1 of 1 ends
        |context after
    """.trimMargin()

    val diffSide1First = """
        |context before
        |<<<<<<< conflict 1 of 1
        |+++++++ abc123 "side A"
        |ours content
        |%%%%%%% diff from: parent "base"
        |\\\\\\\ to: def456 "side B"
        |+theirs content
        |>>>>>>> conflict 1 of 1 ends
        |context after
    """.trimMargin()

    /** GitHub #112's second reported shape: the diff section rendering the destination role. */
    val diffDestinationFirst = """
        |<<<<<<< conflict 1 of 1
        |%%%%%%% diff from: ovknlmro 7d7c6e6b "B1" (parents of rebased revision)
        |\\\\\\\        to: nuvyytnq 5dda2f09 "A" (rebase destination)
        |-base content
        |+destination content
        |+++++++ puqltutt daa6ffd5 "B2" (rebased revision)
        |moved content
        |>>>>>>> conflict 1 of 1 ends
    """.trimMargin()

    val rebaseRoleLabelled = """
        |context before
        |<<<<<<< abc123 "side A" (rebase destination)
        |ours content
        |||||||| parent123 "base" (parents of rebased revision)
        |base content
        |=======
        |theirs content
        |>>>>>>> def456 "side B" (rebased revision)
        |context after
    """.trimMargin()

    val multiBlock = """
        |line1
        |<<<<<<< abc "side A"
        |ours-A
        |||||||| base "parent"
        |base-A
        |=======
        |theirs-A
        |>>>>>>> def "side B"
        |line2
        |<<<<<<< abc "side A"
        |ours-B
        |||||||| base "parent"
        |base-B
        |=======
        |theirs-B
        |>>>>>>> def "side B"
        |line3
    """.trimMargin()

    /** No trailing newline after the file's own final line - EOF lands right at the block's own close. */
    val blockAtEofNoTrailingNewline = buildString {
        append("<<<<<<< abc \"side A\"\n")
        append("ours content\n")
        append("||||||| base \"parent\"\n")
        append("base content\n")
        append("=======\n")
        append("theirs content\n")
        append(">>>>>>> def \"side B\"") // deliberately no trailing \n
    }

    val unterminatedBlock = """
        |before
        |<<<<<<< Conflict 1 of 1
        |+++++++ Contents of side #1
        |ours
        |------- Base
        |base
    """.trimMargin()

    /** A literal `<<<<<<<` inside a side's own content must not be treated as a nested block start. */
    val literalOpenMarkerInsideContent = """
        |<<<<<<< abc "side A"
        |ours content
        |<<<<<<< not a real marker, just content
        |more ours content
        |||||||| base "parent"
        |base content
        |=======
        |theirs content
        |>>>>>>> def "side B"
    """.trimMargin()
}
