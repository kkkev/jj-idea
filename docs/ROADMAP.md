# Jujutsu IntelliJ Plugin - Development Roadmap

This document provides a prioritized feature roadmap with a logical progression: read-only features first, then simple writes, then remote operations, and finally complex operations.

**Last Updated**: 2025-12-02

---

## Current Status (MVP Complete)

**Completed Features**:
- ✅ Basic VCS integration extending AbstractVcs
- ✅ Working copy status detection via ChangeProvider
- ✅ Diff viewing with DiffProvider for working copy changes
- ✅ Describe-first workflow UI (left tool window)
- ✅ File status colors (Blue/Green/Gray for Modified/Added/Deleted)
- ✅ Tree view with directory grouping and expand/collapse
- ✅ Editable working copy in diff view
- ✅ CLI-based command execution with interface abstraction
- ✅ Basic log panel with commit history parsing
- ✅ Describe and New Change buttons

**Architecture Validation**:
- ✅ Single `JujutsuCommandExecutor` - Simpler and better than SVN's multi-client factory
- ✅ Provider/Environment pattern correctly implemented
- ✅ Async execution with proper EDT/background threading
- ✅ Tool window organization appropriate for JJ's describe-first workflow

---

## Development Philosophy

**Prioritization Logic**:
1. **Phase 1: Read-only features** - Safe, build understanding before modifying state
2. **Phase 2: Simple write operations** - Essential daily operations (describe, new, bookmarks)
3. **Phase 3: Remote operations** - Collaboration with git remotes
4. **Phase 4: Complex operations** - Advanced features (conflicts, rebase, graph visualization)

This order minimizes risk while delivering value incrementally.

---

## Phase 1: Read-Only Features (1-2 weeks, ~10-12 days)

**Goal**: Complete the read-only VCS experience - view current state, history, and diffs without modifying anything.

### 1. Show Current Change Details [#10](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/10)

**Priority**: HIGH | **Effort**: 1 day

Display the current change (@) details prominently in the tool window.

**What to show**:
- Change ID (short form)
- Full description (multi-line)
- Parent change IDs
- Whether it's empty

**Why**: Users need to see what change they're currently working on.

**Implementation**: Use existing `JujutsuLogParser` with `jj log -r @`.

---

### 2. Enhanced Log View [#11](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/11)

**Priority**: HIGH | **Effort**: 2-3 days

Improve the existing `JujutsuLogPanel` with better UI and navigation.

**Enhancements needed**:
- Show last 50 changes (configurable)
- Better table/list formatting
- Double-click to see full change details
- Scroll to load more history
- Show bookmarks on changes

**Current status**: Basic log panel exists but needs UI improvements.

---

### 3. Show Files in Historical Change [#12](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/12)

**Priority**: HIGH | **Effort**: 2-3 days

When viewing a historical change in the log, show which files were modified.

**User experience**:
- Select change in log → see files in that change
- Tree structure (like working copy view)
- Same grouping/filtering options
- Add/modify/delete status with colors

**Commands**: `jj log -r <change-id>` with file listing template or `jj diff --summary`.

---

### 4. View Diffs from Historical Changes [#13](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/13)

**Priority**: HIGH | **Effort**: 2-3 days

Show diffs for files in historical changes (not just working copy).

**User experience**:
- Click file in historical change → see diff
- Compare change vs parent by default
- Can compare any two changes
- Read-only diff view

**Commands**: `jj file show -r <change-id> <path>` for each revision, then diff.

**Depends on**: Issue #12 (Show files in historical change)

---

### 5. File History View (VcsHistoryProvider) [#14](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/14)

**Priority**: HIGH | **Effort**: 2-3 days

Implement IntelliJ's standard file history interface.

**User experience**:
- Right-click file → "Show History"
- View all changes that touched the file
- IntelliJ's built-in history UI

**Why**: Integrates with IntelliJ's standard VCS UI patterns.

**Implementation**: `VcsHistoryProvider` interface, runs on background thread.

---

### 6. Real-time Status Updates [#15](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/15)

**Priority**: MEDIUM | **Effort**: 1-2 days

Auto-refresh working copy status when files change.

**User experience**:
- File changes appear immediately
- No manual refresh needed

**Implementation**: `VirtualFileListener` → mark files dirty → triggers ChangeProvider.

---

## Phase 2: Simple Write Operations (1-2 weeks, ~7-10 days)

**Goal**: Essential daily operations for working with changes.

### 7. Improved Describe Command [#16](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/16)

**Priority**: HIGH | **Effort**: 1 day

Enhance existing "Describe" button with validation and feedback.

**Improvements**:
- Validate description not empty
- Error notifications on failure
- Success notifications
- Update current change display after success

**Current status**: Basic describe button exists, needs polish.

---

### 8. Enhanced New Change Command [#17](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/17)

**Priority**: HIGH | **Effort**: 1-2 days

Improve "New Change" with options.

**Enhancements**:
- Dialog for options
- Optional description field
- Create after current vs. after parent
- Success notification with new change ID

**Commands**: `jj new`, `jj new -m "desc"`, `jj new -A <change-id>`

---

### 9. Bookmark Management UI [#18](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/18)

**Priority**: HIGH | **Effort**: 2-3 days

Create/delete/rename/move bookmarks (JJ's branches).

**User experience**:
- Bookmarks shown in log view
- Context menu: Create, Delete, Rename, Move
- Visual bookmark indicators

**Commands**: `jj bookmark create/delete/rename/set`

**Why**: Essential for organizing work and collaboration.

---

### 10. File Ignoring Support [#19](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/19)

**Priority**: MEDIUM | **Effort**: 1-2 days

Support `.gitignore` for excluding files.

**User experience**:
- Ignored files grayed out
- Context menu: Add/Remove from .gitignore

**Note**: JJ uses Git's `.gitignore` format automatically.

---

### 11. Settings/Configuration UI [#20](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/20)

**Priority**: MEDIUM | **Effort**: 1-2 days

Configurable settings panel.

**Settings**:
- JJ executable path
- Auto-refresh toggle
- Change ID display format (short/long)
- Number of changes in log

**Location**: Settings → Version Control → Jujutsu

---

## Phase 3: Remote Operations (1 week, ~3-5 days)

**Goal**: Enable collaboration via Git remotes.

### 12. Git Remote Operations (Fetch/Push) [#21](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/21)

**Priority**: HIGH | **Effort**: 2-3 days

Fetch and push to Git remotes.

**User experience**:
- "Fetch" action in VCS menu
- "Push" action with bookmark selection
- Progress indication
- Incoming/outgoing changes indication

**Commands**: `jj git fetch`, `jj git push --bookmark <name>`

---

### 13. Git Remote Management [#22](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/22)

**Priority**: MEDIUM | **Effort**: 1-2 days

Manage Git remotes (add, remove, rename).

**User experience**:
- List all remotes
- Add new remote with name and URL
- Remove/rename remotes

**Commands**: `jj git remote list/add/remove/rename`

---

## Phase 4: Complex Operations (2-3 weeks, ~15-20 days)

**Goal**: Advanced features requiring more sophisticated UI and error handling.

### 14. Conflict Resolution UI [#23](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/23)

**Priority**: HIGH | **Effort**: 3-4 days

Detect and resolve merge conflicts visually.

**User experience**:
- Conflicted files show RED
- 3-way merge editor
- "Mark Resolved" action

**Implementation**: `MergeProvider` interface, parse conflict markers.

**Why Phase 4**: Requires sophisticated UI and error handling.

---

### 15. File Blame/Annotation [#24](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/24)

**Priority**: MEDIUM | **Effort**: 3-5 days

Show which change introduced each line of code.

**User experience**:
- Right-click in editor → "Annotate with Jujutsu"
- Gutter shows change IDs per line
- Hover for full description

**Jujutsu advantage**: Change IDs are stable across rebases!

**Commands**: `jj file annotate <path>`

**Implementation**: `AnnotationProvider` interface.

---

### 16. Change Graph Visualization [#25](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/25)

**Priority**: MEDIUM | **Effort**: 5-7 days

Graphical display of change ancestry.

**User experience**:
- Visual graph in log view
- See parent-child relationships
- Click nodes to navigate

**Complexity**: Requires custom graph rendering or VcsLogProvider integration.

---

### 17. Rebase/Squash Operations [#26](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/26)

**Priority**: LOW | **Effort**: 4-6 days

Visual rebase, squash, and split operations.

**User experience**:
- Select change → "Rebase onto..."
- "Squash into Parent" action
- "Split Change" with interactive mode

**Commands**: `jj rebase -r <id> -d <dest>`, `jj squash`, `jj split`

**Why Phase 4**: Complex operations that can result in conflicts.

---

## Summary: Development Timeline

| Phase | Focus | Issues | Effort | Timeline |
|-------|-------|--------|--------|----------|
| **Phase 1** | Read-only features | #10-15 | 10-12 days | 2-3 weeks |
| **Phase 2** | Simple write operations | #16-20 | 7-10 days | 1-2 weeks |
| **Phase 3** | Remote operations | #21-22 | 3-5 days | 1 week |
| **Phase 4** | Complex operations | #23-26 | 15-20 days | 3-4 weeks |
| **Total** | **All features** | **17 issues** | **35-47 days** | **7-10 weeks** |

---

## Recommended Implementation Order

**Immediate priorities** (start here):
1. #10 - Show current change details (1 day) - Quick win, high value
2. #11 - Enhanced log view (2-3 days) - Foundation for history features
3. #12 - Show files in historical change (2-3 days) - Builds on log view
4. #13 - View diffs from historical changes (2-3 days) - Completes read-only experience

**After Phase 1 core (issues #10-13)**:
5. #14 - File history provider (2-3 days) - IntelliJ integration
6. #15 - Real-time updates (1-2 days) - Quality of life

**Then Phase 2** (simple writes):
7. #16 - Improved describe (1 day) - Polish existing feature
8. #17 - Enhanced new command (1-2 days) - Polish existing feature
9. #18 - Bookmark management (2-3 days) - Essential for collaboration

---

## Success Metrics

**Phase 1 Complete** when:
- Users can view complete change history
- Users can see files and diffs in any historical change
- Per-file history works via right-click

**Phase 2 Complete** when:
- Users can manage bookmarks visually
- Describe and New have proper validation
- Basic configuration available

**Phase 3 Complete** when:
- Users can fetch/push to Git remotes
- Remote management works

**Phase 4 Complete** when:
- Conflict resolution UI works
- Blame/annotation available
- Advanced operations (rebase/squash) supported

---

## Technical Debt & Future Enhancements

### Known Issues
1. VcsRootChecker disabled - API changed in IntelliJ 2025.2
2. CheckinEnvironment returns null - intentional for describe-first workflow

### Potential Future Features
1. **Change templates** - Configurable templates for descriptions
2. **Parallel changes** - UI for working on multiple changes simultaneously
3. **Operation log** - Show history of JJ operations (like Git reflog)
4. **Undo/Redo** - Leverage JJ's operation log for undo
5. **AI-assisted descriptions** - Generate descriptions from diffs (LLM integration)

---

## References

### Documentation
- [CLAUDE.md](../CLAUDE.md) - Complete project documentation
- [VCS_API_REFERENCE.md](VCS_API_REFERENCE.md) - IntelliJ VCS integration reference

### Jujutsu Resources
- [Official Tutorial](https://jj-vcs.github.io/jj/latest/tutorial/)
- [JJ Best Practices](https://zerowidth.com/2025/jj-tips-and-tricks/)
- [Chris Krycho's JJ Init](https://v5.chriskrycho.com/essays/jj-init)

### IntelliJ Platform
- [Plugin SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [VCS Integration](https://plugins.jetbrains.com/docs/intellij/vcs-integration.html)

### Other JJ IDE Plugins
- [Selvejj](https://plugins.jetbrains.com/plugin/28081-selvejj) - IntelliJ
- [VisualJJ](https://www.visualjj.com/) - VSCode
- [Jujutsu Kaizen (jjk)](https://marketplace.visualstudio.com/items?itemName=jjk.jjk) - VSCode

---

## GitLab Issue Links

All issues are tracked in GitLab with detailed acceptance criteria and implementation notes:

**Phase 1**: [#10](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/10) | [#11](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/11) | [#12](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/12) | [#13](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/13) | [#14](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/14) | [#15](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/15)

**Phase 2**: [#16](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/16) | [#17](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/17) | [#18](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/18) | [#19](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/19) | [#20](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/20)

**Phase 3**: [#21](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/21) | [#22](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/22)

**Phase 4**: [#23](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/23) | [#24](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/24) | [#25](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/25) | [#26](http://gitlab.home.marigoldfeathers.com/kevin/jj-idea/-/issues/26)
