# Changelog

All notable changes to the Jujutsu VCS Plugin for IntelliJ IDEA will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/kkkev/jj-idea/compare/v0.2.4...HEAD
[0.2.4]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.4
[0.2.3]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.3
[0.2.0]: https://github.com/kkkev/jj-idea/releases/tag/v0.2.0
