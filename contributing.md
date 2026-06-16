## Contributing Changes

When making changes that affect users (features, fixes, behavior changes):

1. Add an entry to the `[Unreleased]` section of `CHANGELOG.md`
2. Use the appropriate section: **Added**, **Fixed**, **Changed**, or **Removed**

CI will fail if you change source code without updating the changelog. For internal changes (refactoring, tests, docs), add `[skip changelog]` to your commit message.

## Release Process (Maintainers)

Run the release script:

```bash
./scripts/release.sh 0.2.0
```

The script will:
- Move `[Unreleased]` content to the new version section
- Update `build.gradle.kts` version
- Verify the build passes
- Create and push the tag

GitHub Actions then creates the GitHub release with changelog notes.

After the release, update `build.gradle.kts` to the next snapshot version (e.g., `0.3.0-SNAPSHOT`).

## Terminology
- A **root** is an IDEA VCS root - a folder in the project that has its own VCS configuration, defined
in IDEA's VCS/Directory Mappings configuration. A Jujutsu root points to the same directory as a Jujutsu repository.
- A **repository** is a Jujutsu repository - the top-level folder of a Jujutsu-controlled directory tree.
- A repository has been **initialised** if it contains a `.jj` directory, and hence Jujutsu is tracking its content.

## Performance & Scale

The plugin targets large repositories (~1M files, ~500k ignored files, ~100k commits,
~1k-change working sets, multi-root projects). The full scale envelope, refresh-path
rules, and JVM-traversal requirements are documented in the **[Performance & Scale
section of CLAUDE.md](CLAUDE.md#performance--scale)**.

**Every PR touching file traversal, log parsing, change-provider logic, or VFS
listener fan-out must answer this checklist question before merge:**

> Does this change do work that grows with any scale dimension — total files in
> the working copy, ignored files, commits in the log, changes in the working
> set, or number of roots? If yes, what bounds it, and where is the scale test?

If the answer is yes: (a) state the complexity in your PR description, and (b)
ship an operation-count test following the `GitignoreScanTest.kt` pattern (see
`src/test/kotlin/in/kkkev/jjidea/vcs/ignore/GitignoreScanTest.kt`).

## Architecture

The plugin is designed with a clean separation between the VCS operations and their implementation:

- **`JujutsuCommandExecutor`** interface: Abstraction for jj operations
- **`JujutsuCliExecutor`**: CLI-based implementation (current)
- Future-ready for native library integration

## Building

```bash
./gradlew build
```

## Running in Development

```bash
./gradlew runIde
```

This will launch a new IntelliJ IDEA instance with the plugin installed.

## Task tracking

Tasks (features, bugs etc.) are managed in [Beads](https://github.com/steveyegge/beads).
