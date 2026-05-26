package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry

sealed interface LogClickTarget

data class BookmarkClick(
    val repo: JujutsuRepository,
    val entry: LogEntry,
    val bookmark: Bookmark
) : LogClickTarget
