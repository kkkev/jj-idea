# Roadmap

## Planned Features

Roughly in priority order.

1. **Hunk-Level Squash** — Line/hunk granularity for moving changes between commits, plus a live preview panel for the Squash Into dialog
2**Large Repository Support** — Actions and dialogs that currently fail silently when the target commit falls outside the loaded window (annotation history, compare, split, squash); plus smarter refresh (cache immutable commits, suppress redundant reloads on file edits)
3**Issue Tracker Links** — Render `JIRA-123` / `#123` references in commit descriptions as clickable links via IntelliJ's `IssueNavigationConfiguration`
4**Operation Log & Undo** — Browse `jj op log`, undo/redo with toolbar buttons
5**Remote Management** — Add/remove/rename Git remotes, provider-aware clone with authentication
6**JJ-Unique Operations** — Evolog (change evolution), duplicate, absorb, interdiff, merge signposting
7**Forge Integration** — Create and view GitHub/GitLab pull requests and merge requests from the IDE

Have a feature request? [Open an issue](https://github.com/kkkev/jj-idea/issues).
