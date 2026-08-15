package `in`.kkkev.jjidea.jj.cli

/** Escapes a string for use inside a jj double-quoted string literal (fileset or template). */
internal fun String.escapeJjString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Wraps a repo-root-relative path as a literal jj fileset argument.
 *
 * jj commands parse bare positional path arguments as fileset expressions, so
 * meta-characters — `()[]~|&`, quotes, whitespace — are misinterpreted as operators
 * rather than matched literally. For example `frontend/src/app/(app)/users/[id]/file.tsx`
 * (a Next.js app-router path) fails to parse as a fileset at all (see GitHub #73).
 *
 * `cwd:"..."` forces a literal, cwd-relative match. It is not the same as wrapping in bare
 * quotes: an unprefixed `"..."` fileset literal still defaults to `prefix-glob:`, so e.g.
 * `[id]` would be parsed as a glob character class and silently match nothing. `cwd:` also
 * preserves today's bare-path recursive-prefix semantics (matches the file, or everything
 * under it if it's a directory), unlike `file:`/`cwd-file:` which match only an exact file.
 */
internal fun String.toFileset(): String = "cwd:\"${escapeJjString()}\""
