# Jujutsu VCS IntelliJ IDEA Plugin — Agent Instructions

This is a Kotlin IntelliJ IDEA plugin for [Jujutsu](https://jj-vcs.github.io/jj/) (jj)
version control.

**Read [contributing.md](contributing.md) before making changes** — it has the full
architecture, design decisions, coding standards, testing strategy, performance/scale
rules, git workflow, and release process. Everything below is agent-specific operating
rules that supplement it.

## Operating Rules

- **Use `jj`, never `git`**, for version control operations.
- **Use beads for all task tracking** (`bd create`, `bd ready`, `bd close`). Never use
  TodoWrite/TaskCreate for this project — "issues" and "tasks" mean beads issues.
- **Ask before pushing.** Never run `jj git push` (or `git push`) without explicit
  confirmation first.
- **Prefer automated tests.** Use the manual checklist
  ([docs/manual-tests.md](docs/manual-tests.md)) only as a fallback for surfaces that
  can't be automated (see contributing.md § Testing Strategy).
- **Scale analysis is a deliverable.** Any change adding a filesystem traversal or a
  per-file/per-commit loop must state its complexity against the scale envelope and ship
  an operation-count test — see contributing.md § Performance & Scale.
- **After any UI-affecting change**: report the exact manual smoke steps, and update
  [docs/manual-tests.md](docs/manual-tests.md) if it adds or changes a manual-testable
  surface.
- Follow contributing.md's End of Task Checklist before declaring work done.
- **When the user says "finish"**, run in order: `./gradlew check` → update `CHANGELOG.md`
  (add a user-facing entry under `[Unreleased]`) → `jj describe` (write a commit message
  for the working-copy change) → `bd export -o .beads/issues.jsonl` →
  `jj bookmark set master` → then stop and ask for permission before any `jj git push`.
