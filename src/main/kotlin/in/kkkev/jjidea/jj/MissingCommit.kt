package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.JujutsuBundle

/** jj's error for a revision that doesn't resolve; only a full commit id (hex) is a commit-id read gone missing. */
private val MISSING_COMMIT = Regex("""Revision `([0-9a-f]{40,64})` doesn't exist""")

/**
 * "Commit no longer available" for a read of a commit id jj can't find, else null (jj-idea-wiz0). Reads address full
 * commit ids, so this is a clear message instead of jj's bare stderr; change ids are never hex, so they don't match.
 */
internal fun missingCommitMessage(stderr: String): String? =
    MISSING_COMMIT.find(stderr)?.let { JujutsuBundle.message("commit.unavailable", it.groupValues[1].take(12)) }
