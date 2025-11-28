# GitLab Project Setup Instructions

This guide will help you create a GitLab project for the Jujutsu IntelliJ IDEA plugin and set up issues.

## Prerequisites

- Git repository initialized (✓ already done)
- GitLab account on gitlab.com or self-hosted instance
- `glab` CLI tool installed (✓ already installed)

## Step 1: Authenticate with GitLab

Choose your GitLab instance and authenticate:

### For gitlab.com
```bash
glab auth login --hostname gitlab.com
```

### For self-hosted GitLab
```bash
glab auth login --hostname gitlab.home.marigoldfeathers.com
```

Follow the prompts to authenticate via web browser or personal access token.

## Step 2: Create GitLab Project

### Option A: Using GitLab CLI (Recommended)

```bash
# Create a new project
glab repo create jj-idea \
  --description "Native IntelliJ IDEA plugin for Jujutsu VCS" \
  --public \
  --defaultBranch main

# This will create the project and set up the remote
```

### Option B: Using GitLab Web UI

1. Go to https://gitlab.com (or your GitLab instance)
2. Click "New Project" → "Create blank project"
3. Fill in details:
   - **Project name:** jj-idea
   - **Project slug:** jj-idea
   - **Visibility:** Public (or Private as needed)
   - **Initialize repository with a README:** NO (we already have one)
4. Click "Create project"

Then add the remote manually:
```bash
# Replace <username> with your GitLab username
git remote add origin git@gitlab.com:<username>/jj-idea.git

# Or for HTTPS:
git remote add origin https://gitlab.com/<username>/jj-idea.git
```

## Step 3: Push Code to GitLab

```bash
# Add all files to git
git add .

# Create initial commit (if not already done)
git commit -m "Initial commit: Jujutsu IntelliJ IDEA plugin

Implements basic VCS integration for Jujutsu (jj) including:
- Working copy changes view
- Describe-first workflow
- Diff view
- Basic log view
- Commit management

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>"

# Push to GitLab
git push -u origin main
```

## Step 4: Create Issues

### Method 1: Using the glab CLI (Fast)

After authenticating, you can use these commands to create all issues at once:

```bash
# Navigate to your project directory
cd /Users/kevin/workspace/jj-idea

# Issue 1: Version detection
glab issue create \
  --title "Version detection and compatibility for jj commands" \
  --label "enhancement,compatibility,priority::high" \
  --description "Different versions of Jujutsu (jj) have different template keywords and command syntax.

**Tasks:**
- [ ] Detect jj version via \`jj --version\`
- [ ] Map template keywords to correct version (e.g., \`working_copy\` vs \`current_working_copy\`)
- [ ] Use version-appropriate command syntax
- [ ] Show warning if jj version is too old or unsupported

**Acceptance Criteria:**
- Plugin detects jj version on startup
- Template keywords chosen based on detected version
- Works with jj 0.20.0+
- User warned if jj version is incompatible

See GITLAB_ISSUES.md for full details."

# Issue 2: Fix preview
glab issue create \
  --title "Fix file preview functionality" \
  --label "bug,ui,preview,priority::medium" \
  --description "The file preview feature needs improvements:

**Tasks:**
- [ ] Single-click should show preview in preview tab
- [ ] Preview should not create permanent tabs
- [ ] Preview should work with IntelliJ preview tab system
- [ ] Consider showing diff preview

**Acceptance Criteria:**
- Single-click opens file in preview tab
- Preview tab replaced when another file is selected
- Preview behavior matches Git plugin
- Diff preview option available

See GITLAB_ISSUES.md for full details."

# Issue 3: Implement log
glab issue create \
  --title "Complete implementation of commit log view" \
  --label "feature,log,ui,priority::high" \
  --description "The log view (History tab) needs completion:

**Tasks:**
- [ ] Display all commits correctly
- [ ] Show commit graph/relationships
- [ ] Display commit metadata (author, timestamp)
- [ ] Allow filtering and searching commits
- [ ] Support pagination for large repositories

**Acceptance Criteria:**
- Commit log shows all commits with correct ordering
- Visual graph shows parent/child relationships
- All commit metadata displayed
- Search/filter functionality working
- Performance acceptable for large repositories (1000+ commits)

See GITLAB_ISSUES.md for full details."

# Issue 4: Allow to edit commits
glab issue create \
  --title "Enable commit editing (describe, amend, etc.)" \
  --label "feature,editing,core-workflow,priority::high" \
  --description "Users should be able to edit commits in the history:

**Tasks:**
- [ ] Edit commit description via \`jj describe -r <rev>\`
- [ ] Amend file changes to any commit via \`jj squash\`
- [ ] UI for selecting which commit to edit
- [ ] Show which commit is currently being edited
- [ ] Support editing multiple commits in a session

**Acceptance Criteria:**
- Right-click context menu on commit → Edit Description
- Can edit description of any commit, not just working copy
- Can amend changes to selected commit
- Current edit target clearly indicated in UI
- Changes immediately reflected in log view

See GITLAB_ISSUES.md for full details."

# Issue 5: Show bookmarks in the log
glab issue create \
  --title "Display bookmarks (branches) in commit log view" \
  --label "feature,bookmarks,ui,log,priority::medium" \
  --description "Bookmarks should be visible in the log view:

**Tasks:**
- [ ] Show bookmark names next to commits
- [ ] Color-code local vs remote bookmarks
- [ ] Show current bookmark with special indicator
- [ ] Support multiple bookmarks on same commit
- [ ] Make bookmarks clickable for actions

**Acceptance Criteria:**
- Bookmarks appear in log view next to commit
- Local and remote bookmarks distinguished visually
- Current bookmark highlighted
- Multiple bookmarks handled well
- Bookmark click opens context menu

See GITLAB_ISSUES.md for full details."

# Issue 6: Bookmark management
glab issue create \
  --title "Implement bookmark management UI similar to Git branches" \
  --label "feature,bookmarks,ui,git-parity,priority::high" \
  --description "Create bookmark management system similar to IntelliJ Git branch UI:

**Tasks:**
- [ ] Bookmarks dropdown in toolbar
- [ ] List all local and remote bookmarks
- [ ] Create new bookmark
- [ ] Rename/delete bookmarks
- [ ] Track remote bookmarks
- [ ] Push/pull bookmarks
- [ ] Move working copy to bookmark

**Acceptance Criteria:**
- Bookmarks menu accessible from toolbar
- Shows tree of local/remote bookmarks
- Create: \`jj bookmark create <name>\`
- Delete: \`jj bookmark delete <name>\`
- Move to: \`jj new <bookmark>\`
- Track remote: \`jj bookmark track\`
- Push/pull actions available

See GITLAB_ISSUES.md for full details."

# Issue 7: Create new commit at any place
glab issue create \
  --title "Create new commit at arbitrary position in history" \
  --label "feature,workflow,log,priority::medium" \
  --description "Users should be able to create a new commit anywhere in the commit graph:

**Tasks:**
- [ ] Right-click on any commit → New commit here
- [ ] Execute \`jj new <selected-rev>\`
- [ ] Update working copy view to reflect new position
- [ ] Show clear indication of where working copy is now
- [ ] Explain to user what happened

**Acceptance Criteria:**
- Right-click context menu on any commit
- New commit here action executes \`jj new <rev>\`
- Working copy updates to show new empty commit
- Log view shows new working copy position
- User notification explains the operation

See GITLAB_ISSUES.md for full details."

# Issue 8: Create merge commits
glab issue create \
  --title "Support creating merge commits (multi-parent commits)" \
  --label "feature,merge,workflow,advanced,priority::low" \
  --description "Implement jj merge functionality:

**Tasks:**
- [ ] Select multiple commits in log view (Ctrl+Click)
- [ ] Action: Create merge commit
- [ ] Execute \`jj new <rev1> <rev2> ...\`
- [ ] Handle conflicts if they arise
- [ ] Show merge commit in log graph with multiple parent lines

**Acceptance Criteria:**
- Multi-select commits in log view
- Context menu: Merge these commits
- Executes \`jj new <rev1> <rev2> ...\`
- Conflict resolution UI appears if needed
- Merge commit shown with multiple parents in graph
- Working copy view indicates multi-parent state

See GITLAB_ISSUES.md for full details."

echo "✓ All issues created successfully!"
```

### Method 2: Using GitLab Web UI (Manual)

1. Go to your project on GitLab
2. Navigate to Issues → New Issue
3. For each issue in `GITLAB_ISSUES.md`:
   - Copy the title
   - Copy the description
   - Add the labels
   - Set priority
   - Click "Create issue"

## Step 5: Set up Project Settings (Optional)

### Enable CI/CD

The `.gitlab-ci.yml` file is already in the repository. After pushing, GitLab will automatically detect it and start running pipelines.

### Configure CI/CD Variables

If you plan to publish to JetBrains Marketplace:

1. Go to Settings → CI/CD → Variables
2. Add variable:
   - Key: `JETBRAINS_MARKETPLACE_TOKEN`
   - Value: Your JetBrains Marketplace token
   - Protect variable: Yes
   - Mask variable: Yes

### Set up Project Description

1. Go to Settings → General
2. Project description: "Native IntelliJ IDEA plugin for Jujutsu VCS with describe-first workflow support"
3. Topics/Tags: `intellij-plugin`, `jujutsu`, `vcs`, `kotlin`, `version-control`

### Enable Issue Templates

Create `.gitlab/issue_templates/bug.md`:
```markdown
## Bug Description
A clear description of what the bug is.

## Steps to Reproduce
1. Go to '...'
2. Click on '...'
3. See error

## Expected Behavior
What you expected to happen.

## Actual Behavior
What actually happened.

## Environment
- IntelliJ IDEA version:
- Plugin version:
- jj version: (output of `jj --version`)
- OS:

## Logs
Paste relevant logs from IntelliJ IDEA's log file.
```

Create `.gitlab/issue_templates/feature.md`:
```markdown
## Feature Description
Clear description of the feature you'd like.

## Use Case
Why do you need this feature?

## Proposed Solution
How should this work?

## JJ Command
Which `jj` command(s) would this use?

## Alternatives Considered
What alternatives have you considered?
```

## Step 6: Verify Setup

```bash
# Check remote is set up
git remote -v

# Check if you can access the project
glab repo view

# List all issues
glab issue list

# Check CI/CD pipeline status
glab ci status
```

## Next Steps

1. **Review and organize issues:** Assign issues, set milestones, create epics
2. **Set up project board:** Create a board to track issue progress
3. **Configure merge request settings:** Set up approval rules, merge checks
4. **Add collaborators:** Invite team members to the project
5. **Create milestones:** Set up milestones for releases (e.g., v0.2.0, v0.3.0)
6. **Write contributing guidelines:** Create CONTRIBUTING.md

## Useful Commands

```bash
# View project
glab repo view

# Create issue
glab issue create

# List issues
glab issue list

# View issue
glab issue view <issue-number>

# Create merge request
glab mr create

# View CI status
glab ci status

# Open project in browser
glab repo view --web
```

## Troubleshooting

### Authentication Issues

If you get authentication errors:

```bash
# Check auth status
glab auth status

# Re-authenticate
glab auth login --hostname gitlab.com

# Use token authentication
# Create a Personal Access Token at: https://gitlab.com/-/profile/personal_access_tokens
# Then run:
glab auth login --hostname gitlab.com --token <your-token>
```

### Remote Already Exists

If `git remote add` fails because remote already exists:

```bash
# Check current remotes
git remote -v

# Remove old remote
git remote remove origin

# Add new remote
git remote add origin git@gitlab.com:<username>/jj-idea.git
```

## Project URLs (Update with your actual URLs)

- **Project:** https://gitlab.com/\<username\>/jj-idea
- **Issues:** https://gitlab.com/\<username\>/jj-idea/-/issues
- **Pipelines:** https://gitlab.com/\<username\>/jj-idea/-/pipelines
- **Merge Requests:** https://gitlab.com/\<username\>/jj-idea/-/merge_requests

---

**Need Help?**

- GitLab CLI docs: https://gitlab.com/gitlab-org/cli/-/blob/main/README.md
- GitLab CI/CD docs: https://docs.gitlab.com/ee/ci/
- IntelliJ Plugin Development: https://plugins.jetbrains.com/docs/intellij/welcome.html
