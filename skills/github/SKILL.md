---
name: github
description: >
  Reviews GitHub pull requests — fetches PR diffs, summarizes changes, flags potential
  issues, and provides actionable feedback. Use when asked to review a PR or check
  recent pull requests.
---

# GitHub PR Review Skill

Review GitHub pull requests using the `gh` CLI tool.

## Prerequisites

The `gh` CLI must be installed and authenticated. Test with:

```bash
gh auth status
```

If this fails, tell the user: "GitHub CLI (`gh`) is not authenticated. Run `gh auth login` to set up access."

## Commands

### View PR Details

```bash
gh pr view <PR_NUMBER> --json title,body,author,state,baseRefName,headRefName,additions,deletions,changedFiles,reviews,comments
```

- Returns metadata about the pull request.
- Use to understand the PR context before reviewing the diff.

### Fetch PR Diff

```bash
gh pr diff <PR_NUMBER>
```

- Returns the full diff of all changed files.
- For large PRs, review the diff in sections by file.

### List Changed Files

```bash
gh pr view <PR_NUMBER> --json files --jq '.files[].path'
```

- Returns the list of files modified in the PR.
- Useful for understanding the scope of changes before diving into the diff.

### List Open PRs

```bash
gh pr list --json number,title,author,createdAt,headRefName --limit 10
```

- Returns recent open pull requests.
- Use when the user asks "what PRs are open?" or "any PRs to review?"

### View PR Comments

```bash
gh api repos/{owner}/{repo}/pulls/<PR_NUMBER>/comments
```

- Returns review comments on the PR.

## Review Process

When asked to review a PR:

1. **Fetch PR metadata** using `gh pr view` to understand context (title, description, branch, author).
2. **List changed files** to gauge scope.
3. **Fetch the diff** using `gh pr diff`.
4. **Analyze the changes** and provide a structured review:

### Review Output Format

```
## PR Review: #<number> — <title>

**Author:** <author> | **Branch:** <head> → <base> | **Changes:** +<additions> / -<deletions> across <N> files

### Summary
<1-3 sentence summary of what the PR does>

### Changed Files
- `path/to/file.java` — <brief description of change>

### Findings

#### Issues
- **[file:line]** <description of problem> — <suggested fix>

#### Suggestions
- **[file:line]** <improvement suggestion>

#### Positive
- <things done well>

### Verdict
<APPROVE / REQUEST_CHANGES / COMMENT> — <one-line rationale>
```

## What to Look For

- **Bugs:** null pointer risks, off-by-one errors, race conditions, resource leaks
- **Security:** injection vulnerabilities, hardcoded secrets, missing input validation
- **Style:** naming conventions, code duplication, overly complex logic
- **Tests:** missing test coverage for new code paths, edge cases not covered
- **Config:** unintended changes to build files, dependency versions, or config

## Error Handling

| Error | Response |
|-------|----------|
| `gh: command not found` | "The `gh` CLI is not installed. Install it with `brew install gh`." |
| Authentication failure | "GitHub auth has expired. Run `gh auth login` to re-authenticate." |
| PR not found | "PR #<number> was not found. Check the number and try again." |
| Not in a git repo | "This directory is not a git repository. Navigate to a repo first." |
