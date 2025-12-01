# GitLab Project Setup Instructions

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
