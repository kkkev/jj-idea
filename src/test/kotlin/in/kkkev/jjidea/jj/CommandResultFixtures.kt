package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult

/**
 * Test-only replacement for the old flat `CommandResult(exitCode, stdout, stderr, timedOut)`
 * constructor, for the many tests that stub a [CommandExecutor] result and don't care about the
 * reversibility/timeout distinction the real sealed hierarchy makes. Mirrors the (deleted) main-
 * code shim's mapping: `exitCode == 0` -> untracked success, anything else -> an ordinary exit.
 */
fun commandResult(exitCode: Int, stdout: String = "", stderr: String = ""): CommandResult =
    if (exitCode == 0) {
        CommandResult.Success.Irreversible(stdout, stderr, CommandResult.Success.Irreversible.Reason.NOT_TRACKED)
    } else {
        CommandResult.Failure.Exited(stdout, stderr, exitCode)
    }
