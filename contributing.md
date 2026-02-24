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
