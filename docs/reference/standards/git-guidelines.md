# Git Workflow Guidelines

**CRITICAL: Follow these guidelines to prevent repository damage and maintain clean history.**

Current scope/type authority is the reviewed `.hypertext/commits.json` and current Commit Message Standards; historical examples never override those rules.

This document covers **git workflow, commit sequencing, and change organization**. For commit message composition, see **[Commit Message Standards](./commit-messages.md)**.

---

## Commit Sequencing Principles

### One Logical Change Per Commit

Each commit should represent **one complete, coherent change**. A reader should understand the commit's purpose from its diff alone.

**What constitutes "one logical change":**

| Good (Single Commit)                               | Bad (Should Be Split)                                |
| -------------------------------------------------- | ---------------------------------------------------- |
| Add user authentication endpoint + its tests       | Add auth endpoint + refactor unrelated database code |
| Fix bug in payment processing                      | Fix payment bug + add new reporting feature          |
| Refactor Button component to use new design tokens | Refactor Button + fix unrelated form validation      |
| Update all lint configs for new rule               | Update lint + fix lint errors in unrelated files     |

**The test**: If you removed this commit, would exactly one feature/fix disappear? If removing it would break multiple unrelated things, it's too big.

### Commit Ordering Within a Feature

When implementing a feature that requires multiple commits, order them by **dependency**:

```
1. Infrastructure/types first     → Types, interfaces, shared utilities
2. Core implementation second     → Main feature logic
3. Integration third              → Wiring into existing code
4. Relevant tests and documentation accompany their implementation
   → Each retained commit is complete and independently understandable
```

**Example sequence for "Add workspace sharing":**

```text
feat(sharing): Share journal entries with selected recipients

Include the complete behavior, relevant tests and usage guidance together.
A separate independently useful improvement can be another sharing commit.
```

> **Note**: These examples show only the title line. For message format (type, scope, body structure), see **[Commit Message Standards](./commit-messages.md)**. For valid scopes, see **[Commit Scopes Reference](./commit-scopes.md)**.

### When to Split vs. Combine Changes

**Split into separate commits when:**

- Changes serve different purposes (feature vs. refactor vs. fix)
- Changes affect different subsystems with no shared context
- You want the ability to revert one change without the other
- Changes have different reviewers or approval requirements

**Combine into one commit when:**

- Changes are meaningless without each other (API + its only consumer)
- Test files that only test the code in this commit
- Type definitions used only by this commit's implementation
- Config changes required by this commit's code

When combining multiple related changes, write a commit body that explains how they fit together. See **[Writing Effective Bodies](./commit-messages.md#writing-effective-bodies)** for guidance.

### Refactoring Commits

**CRITICAL: Never mix refactoring with feature changes.**

Refactoring commits should:

- Change code structure without changing behavior
- Be independently reviewable and revertible
- Come **before** the feature that benefits from them

```bash
# ✅ CORRECT: Refactor first, then feature
chore(auth): extract token validation to shared utility
feat(auth): stay signed in securely across sessions

# ❌ WRONG: Mixed refactor and feature
feat(auth): add refresh tokens and refactor validation
```

**Why this matters:**

- If the feature has bugs, you can revert it without losing the refactor
- Reviewers can verify the refactor doesn't change behavior
- Git bisect can identify exactly which commit introduced issues

> **Commit type guidance**: Use `chore` with an informative body for structural changes without behavior change, `feat` for new functionality. See **[Current Commit Types](./commit-messages.md#current-commit-types)** for the complete list.

---

## Staging Safety Rules

- **NEVER use `git add -A` or `git add .`** unless 100% certain of what you're staging
- **PREFER specific file paths**: `git add path/to/specific/file.kt`
- **ALWAYS run `git status` before committing** to review staged files
- **Use `git add -p` for interactive staging** of partial file changes

### Safe Staging Examples

```bash
# ✅ Good - specific files
git add client/sync/src/commonMain/kotlin/SyncManager.kt server/src/main/kotlin/SyncRoutes.kt

# ✅ Good - interactive staging (choose hunks)
git add -p client/feature/editor/src/commonMain/kotlin/EditorViewModel.kt

# ✅ Good - stage entire directory you've reviewed
git add client/database/src/

# ❌ Bad - stages everything indiscriminately
git add -A
git add .
git add *
```

---

## Atomic Workflow Pattern

**For multi-agent safety and clean commits, use this atomic pattern:**

```bash
git restore --staged . && git add file1.kt file2.kt && HYPERTEXT_AGENT_COAUTHOR="Codex <noreply@openai.com>" git commit -F /tmp/reviewed-commit-message.txt
```

> **Message format**: The example above shows the basic structure. For complete formatting rules (72-char limit, body style, footer annotations), see **[Message Anatomy](./commit-messages.md#message-anatomy)**.

**Why this pattern:**

| Benefit                       | Explanation                                                        |
| ----------------------------- | ------------------------------------------------------------------ |
| **Multi-agent safety**        | Prevents Agent A's files from being included in Agent B's commit   |
| **Atomic operation**          | All steps succeed or fail together using `&&`                      |
| **Clean slate**               | `git restore --staged .` clears stale staging from other processes |
| **Explicit staging**          | No surprises from lingering staged files                           |
| **Race condition prevention** | Prevents interleaving from concurrent agents                       |

### Workflow Checklist

1. **Clear staging**: `git restore --staged .`
2. **Stage exact files**: `git add path/to/file.kt` (or `git add -p`)
3. **Review staging**: `git status`
4. **Commit using the atomic pattern** — write message following **[Commit Message Standards](./commit-messages.md)**
5. **Verify commit**: `git log -1` and `git show --stat`

---

## Branch and Pull Request Workflow

Every change reaches `main` through a pull request merged with **Rebase and merge**. This is the
same flow every Hypertext Studio repository uses. History on `main` stays linear: there are no merge
commits and nobody pushes directly to `main`.

```
main
  └── sync-retry-backoff        (short-lived branch, one change)
        ├── complete behavior + relevant tests/documentation
        └── another independently useful change, if needed
              → pull request → Rebase and merge → each commit lands on main as-is
```

### Branches

- **One short-lived branch per change.** Name it after the change in lowercase kebab-case, such as
  `sync-retry-backoff`. Agent sessions keep the branch their worktree was created on.
- **No long-lived feature branches.** Unfinished features hide behind feature flags, not branches.
- **Every commit must be shippable.** A rebase merge replays each commit onto `main` individually,
  so each one has to build and leave `main` working, not just the last one on the branch.

### Keeping a Branch Current

Rebase onto `origin/main`. Never merge `main` into the branch, because that creates a merge commit.

```bash
git fetch origin
git rebase origin/main
```

When a rebase stops on a conflict, resolve the files, then restage **every** path the picked commit
changes before continuing. The [atomic staging chain](#atomic-workflow-pattern) clears the index
first, which would otherwise drop the commit's non-conflicting changes, so record the paths before
clearing:

```bash
paths=$(mktemp)
git diff -z --name-only HEAD > "$paths"
git restore --staged . && xargs -0 git add -- < "$paths" && GIT_EDITOR=true git rebase --continue
```

If someone else is working on the same branch, agree on the rebase with them before rewriting it.

### Opening a Pull Request

1. **Rebase on latest main**: `git fetch origin && git rebase origin/main`
2. **Verify all commits are logical**: `git log --oneline origin/main..HEAD`
3. **Fold fixup commits into their parent**: commit the fix with `git commit --fixup <sha>`, then
   run `GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash origin/main`
4. **Review commit messages**: each follows **[Commit Message Standards](./commit-messages.md)**
   (clear title, body explains why)
5. **Run the checks for what you changed**: the pre-push hook compiles the app and runs the tests
   for affected modules; run any extra suites the change needs before pushing
6. **Push the branch**: `git push -u origin HEAD`. After rewriting a branch that is already pushed,
   use `git push --force-with-lease` (safer than `--force`)
7. **Open the pull request against `main`**: write the description to a file and pass it with
   `gh pr create --base main --body-file <file>`. Follow the repository's pull request template,
   or use `## TL;DR`, `## Changes`, and `## Follow-ups and Next Work` when there is none

Agents ask the developer before pushing a branch or opening a pull request.

### Merging

Merge with **Rebase and merge** (`gh pr merge --rebase`) once CI passes. Do not squash: squashing
collapses the changelog-style commits into one and loses the history the commit standards exist to
keep. Merge commits are disabled for this repository, and GitHub deletes the branch after the merge.

---

## Workflow Rules and Exceptions

- **Trust the tooling** — pre-commit hooks are authoritative
- The hook dispatcher uses the installed Hypertext Git-gate options and runs the
  repository checks registered in `.hypertext/git-hooks.json` in their declared
  order. It verifies each check's digest before execution and forwards the same
  native ref-update transaction to both pre-push stages.
- **Fix, don't bypass** — resolve hook failures instead of skipping
- **No proactive repo-wide validation** — avoid `./gradlew test` for whole repo unless:
  - You changed tooling/configuration that affects the whole repo
- **Local builds are OK** — run `./gradlew :module:test` for the modules you touched if useful
- **Single atomic commit** — don't split commits because hooks failed; fix the failures
- **Re-run the full atomic command** after fixes (unstage → stage → commit)

---

## Absolutely Forbidden Practices

**🚨 THESE ACTIONS ARE FIREABLE OFFENSES:**

| Forbidden                                              | Why                                                                   |
| ------------------------------------------------------ | --------------------------------------------------------------------- |
| `git add -A` / `git add .` / `git add *`               | Stages everything indiscriminately                                    |
| `git reset --hard`                                     | Permanently destroys uncommitted work                                 |
| `git push --force`                                     | Destroys commit history on remote                                     |
| Pushing directly to `main`                             | Skips the pull request, its CI, and its review                        |
| Merge commits (`git merge`, **Create a merge commit**) | Breaks the linear history on `main`                                   |
| `git commit -a`                                        | Commits without proper review                                         |
| `git commit --no-verify`                               | Bypasses critical quality enforcement                                 |
| Dropping failing files after hooks fail                | Hides problems instead of fixing them                                 |
| Commit titles mentioning phases                        | See **[Forbidden Patterns](./commit-messages.md#forbidden-patterns)** |

---

## Damage Prevention

❌ **NEVER USE `git reset --hard` UNDER ANY CIRCUMSTANCES**

- This command **permanently destroys** all uncommitted work
- There is **no legitimate reason** to use this command
- If the user asks for it, explain the dangers and offer safer alternatives

### Safe Alternatives

| Goal                                  | Safe Command                                    |
| ------------------------------------- | ----------------------------------------------- |
| Undo last commit, keep changes staged | `git reset --soft HEAD~1`                       |
| Unstage everything                    | `git reset HEAD` or `git reset --mixed HEAD`    |
| Discard changes in specific file      | `git restore --source=HEAD -- path/to/file`     |
| Create backup before risky operation  | `git branch backup-branch`                      |
| Undo a pushed commit                  | `git revert <commit-hash>` (creates new commit) |

**Never force push** without explicit user permission, and **never to main/master**.

---

## Related Documentation

- **[Commit Message Standards](./commit-messages.md)** — Format, structure, body writing style, examples
- **[Commit Scopes Reference](./commit-scopes.md)** — Valid scope names by module
- **[Project Conventions](../../../CLAUDE.md)** — Full project conventions and standards
