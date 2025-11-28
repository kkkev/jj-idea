# Getting the Commit View Working

## Quick Start

1. **Build and run the plugin:**
   ```bash
   ./gradlew runIde
   ```
   This will launch IntelliJ IDEA 2025.2 with the plugin installed.

2. **Open a jujutsu repository** in the launched IDEA instance

3. **Configure VCS manually**:
   - Go to **Settings** → **Version Control** → **Directory Mappings**
   - Click the **+** button
   - Select your project root directory
   - Choose **Jujutsu** from the VCS dropdown
   - Click **OK**

4. **View changes:**
   - Open the **Commit** tool window:
     - **Alt+0** (Windows/Linux) or **Cmd+0** (Mac)
     - Or **View** → **Tool Windows** → **Commit**
   - Changed files from `jj status` should appear in the Changes pane
   - Files will be grouped by status (Modified, Added, Deleted)

5. **View diffs:**
   - Click on any changed file in the Commit view
   - The diff viewer will show changes against `@-` (parent revision)
   - Double-click to open a full diff window

## What Should Work Now

✅ **File Status Detection**
- Modified files (M)
- Added files (A)
- Deleted files (D)

✅ **Diff Viewing**
- Click any file to see diff against `@-` (parent revision)
- Uses `jj file show` to get file content at revisions

✅ **Changes View Integration**
- Standard IntelliJ Changes tool window
- Organized by change type

## Known Limitations

⚠️ **No Auto-Detection**
- VCS root must be manually configured (VcsRootChecker disabled due to API issues)

⚠️ **Read-Only**
- No commit, amend, or other write operations yet
- This is intentional for the MVP

⚠️ **Basic Status Parsing**
- Assumes simple `jj status` format
- May not handle all edge cases (conflicts, renames, etc.)

## Troubleshooting

### Changes don't appear

1. Check that Jujutsu is in your PATH:
   ```bash
   jj --version
   ```

2. Verify VCS is configured:
   - Settings → Version Control → Directory Mappings
   - Should show your project with "Jujutsu"

3. Check IDEA logs:
   - Help → Show Log in Finder/Explorer
   - Look for "Jujutsu" related messages

### Diffs don't load

1. Make sure files exist in `@-` revision
2. Check that `jj file show` works:
   ```bash
   jj file show -r @- path/to/file.txt
   ```

3. Enable debug logging:
   - Help → Diagnostic Tools → Debug Log Settings
   - Add: `#in.kkkev.jjidea`

## Next Steps for Development

1. **Fix VcsRootChecker** - Investigate correct VcsKey API for IntelliJ 2024.1+
2. **Improve Status Parsing** - Handle renames, conflicts, etc.
3. **Add Commit History View** - Show `jj log` as a graph
4. **Add Write Operations** - Commit, describe, etc.
5. **Add Configuration UI** - jj executable path, etc.
