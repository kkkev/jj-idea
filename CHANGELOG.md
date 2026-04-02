# Changelog

All notable changes to the Jujutsu VCS Plugin for IntelliJ IDEA will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Describe working copy no longer throws write-only access error

## [0.5.15] - 2026-04-02

### Added
- Truncation indicator in log panel when the entry count hits the limit, with a clickable link to open settings
- Log automatically reloads when the limit setting is changed

### Fixed
- Support custom jj builds with version strings like "jj companyname-0.39.0-..." (GitHub #7)
- Conflicted bookmarks no longer break lists of all bookmarks

### Changed
- Default log limit increased from 50 to 500, with automatic migration for existing users who never changed the default

## [0.5.14] - 2026-04-02

## [0.5.13] - 2026-04-02

### Added
- User feedback after successful push — now shows a notification with the push output
- Can now push untracked bookmarks

### Fixed
- Respect remote and bookmark selections when pushing to remote

## [0.5.12] - 2026-04-01

## [0.5.11] - 2026-03-31

### Added
- Roadmap of upcoming features
- Squash/split from file context menus: right-click files in working copy panel, commit details, project view, or editor to squash into parent or split into a new change with those files pre-selected
- Track/untrack remote bookmarks: right-click a remote bookmark in the log context menu to track or untrack it
- Visual distinction between tracked (filled) and untracked (outline) bookmark icons in the log and push dialog

### Fixed
- UI freeze when VCS operations triggered from modal dialogs (e.g., push dialog) by propagating correct modality state to async callbacks
- Project view and editors now refresh after VCS operations (edit, new, abandon, rebase, squash, split, fetch) that change working copy files on disk

### Changed
- Resized mutable/immutable icons in the log to be less prominent

## [0.5.10] - 2026-03-30

### Added
- Git clone with Jujutsu: Clone Git repositories directly from File > New > Project from Version Control using `jj git clone`
- Initial jj setup: Prompt to configure user.name and user.email when jj is detected but not configured, pre-populating from git global config

## [0.5.9] - 2026-03-29

### Added
- jj installation detection: Plugin now detects jj in PATH and common installation locations (Homebrew, Cargo, Scoop, Chocolatey, Winget, Snap, APT)
- jj availability notifications: Shows helpful guidance when jj is not found, version is too old (requires 0.37.0+), or configured path is invalid
- Installation guidance: Notifications include one-click actions to copy install/upgrade commands or open documentation

## [0.5.8] - 2026-03-27

### Fixed
- Settings changes (jj executable path) now take effect immediately without IDE restart ([#4](https://github.com/kkkev/jj-idea/issues/4))

## [0.5.7] - 2026-03-24

### Changed
- Made accented icon to represent a repository

## [0.5.6] - 2026-03-24

### Fixed
- Fix bookmark icons inconsistent in dark mode (wrong size and fill)

### Added
- Capability to split commits
- Icons for describe, new change, rebase, split, and squash actions
- Icons in HTML panels now scale correctly inside `smaller` blocks

## [0.5.5] - 2026-03-22

### Fixed
- Fix squash into parent not selecting parent change in log after operation
- Fix HTML icon sizing and alignment at different zoom levels

## [0.5.4] - 2026-03-18

### Fixed
- Show history for selection: fix AssertionError caused by missing block history provider
- Remove deprecated/252-only API usages (ActionUtil.performAction, Disposer.isDisposed)
- Fix occasional error caused by catching ProcessCanceledException in change provider

### Changed
- Minimum supported version lowered back to IntelliJ IDEA 2025.1

## [0.5.3] - 2026-03-18

### Added
- Clicking on change ids in working copy and description panels selects the change in the log

### Fixed
- Remove remaining internal API usages flagged by Plugin Verifier (ActionToolbarImpl, VcsUserImpl)
- Performance: Remove double refreshing of the log per VCS operation
- Performance: Remove wasted initial load of repository state
- Bookmark list: Filter deleted bookmarks that were previously throwing errors
- Move bookmark: Remove remote bookmarks from the list of candidate bookmarks to move
- Move bookmark: Fix popup title

## [0.5.2] - 2026-03-10

### Fixed
- Fix icon colorization compatibility break across platform versions
- Replace deprecated and internal API usages flagged by Plugin Verifier

### Changed
- Minimum supported version is now IntelliJ IDEA 2025.2

## [0.5.1] - 2026-03-09

### Fixed
- Replace deprecated `preload="true"` service attribute with `ProjectActivity` to pass JetBrains Marketplace validation

## [0.5.0] - 2026-03-09

### Added
- Apache 2.0 license
- Updated README with screenshots and current feature documentation

### Fixed
- Save all open editor files before executing JJ commands to prevent race conditions with unsaved changes
- Log now refreshes after abandoning a non-working-copy change, and selects the abandoned change's parent instead of jumping to the working copy

## [0.4.2] - 2026-03-08

## [0.4.1] - 2026-03-08

### Added
- Hide `.jj` directory from Project tool window (added to IDE ignored files list on startup)

### Fixed
- Descriptions starting with hyphens (e.g., markdown bullet points) no longer fail with "unexpected argument" error

## [0.4.0] - 2026-03-07

### Added
- Bookmark management: create, delete, rename, and move bookmarks from log context menu
- Move bookmark confirmation dialog when moving backwards or sideways (non-descendant change)

## [0.3.6] - 2026-03-02

## [0.3.5] - 2026-03-02

### Added
- Squash into Parent action: squash a change into its parent with file selection, description editing, and keep-emptied option
- Platform test infrastructure: `@Tag("platform")` tests run with full IntelliJ bootstrap via `./gradlew platformTest`

### Fixed
- Fix "Write-unsafe context" error when clicking files in working copy panel

## [0.3.4] - 2026-02-28

### Fixed
- Fix log table rows expanding on hover, causing graph+description to overpaint adjacent columns
- Performance: sped up log loading for large repositories

### Added
- Git remote operations: Fetch and Push actions accessible from log toolbar, context menu, and VCS menu
- Push dialog with remote selector, bookmark scope options (tracking/specific/all)
- Progress bar feedback for network operations (fetch/push)

## [0.3.3] - 2026-02-26

### Fixed
- Fix IDE freeze caused by duplicate background loading tasks and stuck loading state on cancellation
- Fix annotation click "Searching commit in the log" never completing — now selects in custom log
- Fix `SwingUtilities.invokeLater` misuse causing Graphics2D paint corruption (must use `ApplicationManager.invokeLater` in plugins)
- Fix re-entrant data loads by keeping `loading` flag set during EDT callback in `BackgroundDataLoader`
- Remove redundant `updateRootFilterVisibility()` call with stale data from state listener
- Reduce startup CLI overhead by passing limit revsets to standard VCS log provider
- Fix disposal-safety in state model catch-up handler (prevent stale callbacks on project close/reopen)
- Remove unnecessary `invokeLater` from data loader callbacks to prevent stale table updates
- Format change ids correctly in the log

### Changed
- Reduce vertical whitespace in details panel 

## [0.3.1] - 2026-02-24

### Fixed
- Fix crash on IntelliJ 2025.1 caused by `IconUtil.colorize` signature change in 2025.2

## [0.3.0] - 2026-02-24

### Added
- Rebase dialog with full `jj rebase` support: source modes (-r/-s/-b), destination modes (-d/-A/-B), multi-select source and destination
- Rebase dialog: log-style destination picker with commit graph, bookmarks as decorations, and search by change ID, description, or bookmark name
- Rebase dialog: simulated post-rebase graph preview showing reparented commits with source/destination highlighting
- Rebase dialog: split layout with destination picker on the left and live preview on the right
- Rebase dialog: invalid destinations (self-rebase, cycle-creating targets) are automatically filtered based on source mode

### Fixed
- Fix: log window text filter
- Fix: renames and moves are now tracked properly

## [0.2.5] - 2026-02-15

### Fixed
- Performance: simplified UI updates from file changes

## [0.2.4] - 2026-02-12

### Added
- Custom file history panel with same styling as the log view (replaces standard Show History action)
- Diagnostic logging for state model, change provider, and CLI executor to aid freeze investigation

### Changed
- Declared `.jj` as administrative area in plugin.xml (hides from project view, consistent with Git plugin)

### Fixed
- Performance: most UI action classes now use ActionUpdateThread.BGT to avoid EDT contention during toolbar updates
- Performance: BulkFileListener now filters out `.jj` internal directory changes to prevent unnecessary refresh cycles
- Performance: repository state equality excludes volatile timestamps to prevent spurious invalidations
- Fix: log panel now refreshes after VCS operations (abandon, edit, new) even when working copy unchanged
- Fix: only mark repos dirty when their state actually changes, reducing FileStatusManager event flooding
- Fix: register IgnoredFileProvider for `.jj/` directories to prevent change list processing of internal files
- Fix: downgrade diff summary failure from SEVERE to WARN (expected when viewing abandoned commits)

## [0.2.3] - 2026-02-11

### Fixed
- Graph rendering: fix phantom passthrough lanes where fork+merge connections drew disconnected vertical lines
- Graph rendering: correct lane assignments and passthrough lines for merge commits with intervening side branches
- Keyboard shortcut error: use `meta` instead of `command` for macOS keyboard shortcuts

## [0.2.0] - 2025-02-09

First release under structured release management. This release consolidates
numerous improvements made during the 0.1.x development cycle.

### Added
- Multi-root repository support with unified log window and gutter column
- Change action menu system for context menu operations
- Multi-select files in diff view
- Restore actions for files and revisions
- Platform Annotate action integration for keyboard shortcut support
- Test coverage for custom log tab feature parity
- `jj init` action for initializing repositories
- Release management: CHANGELOG.md, CI changelog check, release script
- CI enforces changelog updates for source changes (skip with `[skip changelog]`)

### Fixed
- Log ordering: use topological sort instead of timestamp sort
- Bookmark names now include remote suffix
- EDT freeze: moved graph building and entry merging off UI thread
- Empty author filter selection no longer causes errors
- VirtualFile.jujutsuProject properly matches files under JJ roots
- Empty marker updates when editing a change
- Auto-select working copy in log after VCS operations
- Handle removed commits gracefully in detail pane
- Hide repo selector dropdown for single-root projects
- Disposal error when opening native log pane in mixed VCS project
- VCS log cache assertion error
- Notification shown when JJ VCS root is not initialized

### Changed
- Removed custom VCS log columns in favor of inline graph rendering
- Improved multi-root support throughout the plugin
- Refactored log tab management
- Change hashes from change IDs to commit IDs for platform compatibility

[Unreleased]: https://github.com/kkkev/jj-idea/compare/v0.5.15...HEAD
[0.5.15]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.15
[0.5.14]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.14
[0.5.13]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.13
[0.5.12]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.12
[0.5.11]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.11
[0.5.10]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.10
[0.5.9]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.9
[0.5.8]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.8
[0.5.7]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.7
[0.5.6]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.6
[0.5.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.5
[0.5.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.4
[0.5.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.3
[0.5.2]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.2
[0.5.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.1
[0.5.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.5.0
[0.4.2]: https://github.com/kkkev/jj-idea/releases/tag/v0.4.2
[0.4.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.4.1
[0.4.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.4.0
[0.3.6]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.6
[0.3.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.5
[0.3.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.4
[0.3.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.3
[0.3.1]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.1
[0.3.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.3.0
[0.2.5]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.5
[0.2.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.4
[0.2.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.3
[0.2.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.0
