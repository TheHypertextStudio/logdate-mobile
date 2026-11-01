# Commit Message Standards

## Current commit types

Current commits use exactly `feat`, `fix`, or `chore`, with a mandatory domain registered in [`.hypertext/commits.json`](../../../.hypertext/commits.json). The [scope reference](./commit-scopes.md) explains the registry boundaries.

- `feat` introduces a capability; `fix` corrects user-observable behavior. Their titles and bodies describe the delivered user outcome. They form the product changelog after landing on main.
- `chore` covers maintenance, tests, documentation, dependencies and refactoring, using the product domain the work supports. It needs an informative body; implementation details are appropriate for maintenance.
- Keep implementation, relevant tests and necessary usage/migration documentation together. Several coherent commits are useful; do not split documentation or tests merely by activity.
- Use a useful title, a blank line before the body, and terminal metadata trailers. Breaking changes require an explicit `BREAKING CHANGE:` explanation even when the title uses `!`.
- Every contributing agent receives its documented `Co-authored-by` trailer. Set `HYPERTEXT_AGENT_COAUTHOR` on the ordinary commit command. Do not invent identities or attribute work to a human.
- The effective committer uses the approved Studio email. Identity changes need their actual authorization; no automatic non-Studio exception, global identity rewrite or history rewrite.
- Stage explicit reviewed paths and use a message file with ordinary Git hooks. Canonical hooks retain the product's secret scans, exact staged formatting and pre-push compile/coverage/affected-module tests.

```text
fix(sync): Keep routine checks out of live backup updates

Routine checks with nothing to upload stay ordinary. Live backup
updates continue to show real upload progress when enabled.

Co-authored-by: Codex <noreply@openai.com>
```

The reviewed registry must be in the committed baseline before canonical activation. Adding its file does not activate hooks, authorize a policy digest, configure protected CI, or establish delivery. Preserve all existing gates until supported reconciliation completes. Scope additions and technical applicability require a maintainer other than their author; protected CI approval remains separate.

<details>
<summary>Superseded historical message guidance</summary>

The following text is retained only to interpret older commits. Its extra types, examples and older scope rules are not current instructions.

# Commit Message Standards

> **Authoritative reference for writing clear, maintainable commit messages in the LogDate codebase**

Commit messages are the permanent record of why code exists. When someone reads `git log` or runs `git blame` months or years later, the commit message is the only context they have. The diff shows the mechanics; the message explains everything else.

---

## Quick Format Reference

```
type(scope): Brief description

Optional detailed explanation...

Footer annotations if needed.
```

**Constraints**:

- Title: Under 72 characters (displays in `git log --oneline`)
- Mood: Imperative ("Add feature", not "Added feature")
- Specificity: "Fix authentication token expiry" not "Fix bug"

---

## Message Anatomy

### Title Line (Required)

**Format**: `type(scope): description`

The first line is the **only required part** and must follow Conventional Commits format. It forces conciseness about _what_ changed, reserving detailed explanation for the body.

**Why 72 characters?**

- Displays completely in `git log --oneline`
- Email clients wrap at 72 characters
- GitHub shows truncated titles in UI
- Readable in terminals with standard widths

### Body (Optional for Trivial Changes, Required for Meaningful Changes)

Separated from title by blank line, wrapped at 72 characters. This is where context lives: _why_ changes were necessary, what alternatives were considered, what trade-offs were made, what impact results.

The diff shows how code changed; the body explains why.

**When to include a body:**

- ✅ Any commit that changes behavior (features, bug fixes, refactors)
- ✅ Architectural decisions or significant structural changes
- ✅ Trade-offs or alternatives considered and rejected
- ✅ Complex bug fixes explaining root cause
- ❌ Trivial changes (typos, link fixes, formatting) — brief title only

### Footer (Optional)

Special annotations tools parse, placed after body text on separate lines:

- `BREAKING CHANGE: description` — Mark backwards-incompatible changes
- `Fixes #123` or `Closes #456` — Close issues when merged
- `Related to #789` — Reference issues without closing
- `Co-Authored-By: Name <email>` — Credit collaborators

---

## Commit Types

| Type         | Purpose                                                                | Body Should Include                                                                                     |
| ------------ | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| **feat**     | New functionality added (capability that didn't exist before)          | What users can now do, why it matters, how it feels — written like a changelog entry, not a code review |
| **fix**      | Behavior corrected (something wasn't working, now it is)               | What was broken, why it was broken, how fix addresses root cause                                        |
| **refactor** | Code restructured without behavior change                              | Why restructuring was needed (bugs? clarity? enabling future work?)                                     |
| **docs**     | Documentation-only changes (README, comments, API docs)                | Usually self-explanatory; explain if non-obvious                                                        |
| **style**    | Code style changes that don't affect behavior (formatting, whitespace) | Usually no body needed; change is mechanical                                                            |
| **test**     | Tests added or modified                                                | What scenarios now covered, why needed, testing gaps addressed                                          |
| **chore**    | Maintenance tasks (dependencies, build tools, CI/CD)                   | Breaking changes and migration steps if updating dependencies                                           |
| **perf**     | Performance improvements                                               | Before/after metrics when possible, bottleneck identified, approach taken                               |

---

## Message Patterns: What Works and What Doesn't

### Problem 1: Vague Title

Reader can't tell what this commit addresses.

```
❌ feat(api): add new endpoint
   fix(auth): fix bug
   refactor(core): update code
```

**Solution**: Be specific — title should be immediately useful in `git log`

```
✅ feat(api): add workspace membership management endpoint
   fix(auth): prevent session fixation vulnerability
   refactor(core): extract token validation to service
```

---

### Problem 2: Missing Context

Diff alone can't explain why a change exists.

```
❌ fix(auth): prevent session fixation vulnerability
   (no body — future reader doesn't understand the vulnerability)
```

**Solution**: Include context in body — explain the problem, approach, and impact.

```
✅ fix(auth): prevent session fixation vulnerability

The session ID was being reused after authentication, allowing an
attacker to hijack the session by setting a known session ID before
the victim logs in.

Now we regenerate the session ID immediately after successful
authentication, following OWASP Session Management guidelines.

Fixes #1234
```

---

### Problem 3: Feature with No Justification

Reader can't understand why this was needed.

```
❌ feat(api): add workspace membership management endpoint

The endpoint validates emails, assigns roles, and sends notifications.
```

**Solution**: Explain business context and architecture.

```
✅ feat(api): add workspace membership management endpoint

Adds POST /api/workspaces/:id/members to support inviting users
to workspaces. This was needed for the workspace collaboration
feature launching in Q2.

The endpoint handles email validation and user lookup, role
assignment (admin, editor, viewer), email notifications for
invitations, and duplicate member prevention.

Related to #567
```

---

### Problem 4: Trivial Changes Embellished Unnecessarily

Not every change needs elaborate explanation.

```
❌ docs: update documentation links in claude command skills

We identified several broken links across multiple files in the
.claude/commands directory. These references were preventing users
from navigating between documentation sections. By updating these
paths we ensure future maintainers can find the correct documentation
without confusion...
```

**Solution**: For truly trivial changes, brief title is sufficient.

```
✅ docs: update documentation links in claude command skills

(No body needed — the diff explains the rest)
```

---

## Body Writing Style

### Inverted Pyramid Structure

Feature commits should read like news articles—**don't bury the lede**:

1. **Lead with impact** — First sentence states what users can now do. Most important information comes first.
2. **Each subsequent paragraph is less critical** — A reader who stops early still gets the key points.
3. **Issue references go last** — Place "Resolves #XXX" at the very end (least salient position).

**Structure (most → least important):**

1. **Title** — The headline: what capability was added
2. **Lede paragraph** — The key impact in 1-2 sentences
3. **Context** — Problem being solved, user pain points addressed
4. **Details** — What changed and how (Added/Improved/Fixed sections if needed)
5. **Reference** — Issue link (least critical, goes last)

### Full Clauses, Not Imperative Fragments

The body should read like documentation, not a todo list.

**Title vs. body style:**

- **Title**: Imperative mood is acceptable ("add filtering to dashboard")
- **Body**: Full clauses with subjects ("The dashboard now supports filtering" or "Users can now filter by project")

```
❌ BAD — Imperative fragments (reads like a checklist):
Added:
- Grid/list view toggle
- Project filter dropdown
- Tags filter with multi-select

✅ GOOD — Full clauses (reads like documentation):
Added:
- The dashboard now supports a compact list view alongside the existing grid,
  making it easier to scan large agent fleets at a glance.
- A project filter dropdown automatically populates from active agent metadata,
  allowing admins to narrow results without manual configuration.
```

Subheadings like "Added:", "Improved:", "Removed:" are fine for organizing larger commits—just ensure each item underneath is a complete sentence, not a noun phrase or imperative fragment.

---

## Writing Effective Bodies

### For Features

**`feat` commits must prioritize end-user behavior.** Write them like a changelog entry that a user of the app would read and understand. Lead with what the user can now do, not what classes were added or what internal plumbing changed.

Answer: What can users do now that they couldn't before? How does the experience feel? Why does it matter?

```
❌ BAD — Sounds like a code review:
feat(workspace): add automatic workspace backups

Add WorkspaceBackupService with daily cron trigger and S3 cold
storage integration. Uses incremental snapshots via BackupEngine.
Wired through DI in WorkspaceModule.

✅ GOOD — Sounds like a changelog:
feat(workspace): add automatic workspace backups

Workspaces are now backed up daily to cold storage. If something
goes wrong, you can restore to any point in the last 30 days.
Backups are incremental so they won't eat through storage quotas.

Addresses the data loss concerns raised in support tickets #456
and #789.
```

The diff already shows the implementation details. The commit message should explain the **experience**, not the **mechanism**.

#### Sneaky Tech Dumps

These look like reasonable commit messages at first glance, but they describe plumbing instead of behavior. A user reading the changelog learns nothing about what changed for them.

```
❌ SNEAKY — Mentions "users" but immediately dives into internals:
feat(timeline): show contextual suggestion cards on timeline

Replace legacy OngoingEvent and SharedMemory suggestion types with
three functional card variants: CompleteDraft navigates to the editor,
MemoryRecall surfaces past entries with a share action via
SharingLauncher, and EmptyDay prompts writing with nearby location.

Remove GetTimelineBannerUseCase, deprecated SuggestedEntryBlock, and
dead SharedMemory domain type. Fix people extraction bug that returned
location names. Wire SharingLauncher through navigation with Koin DI.
```

The title is fine but the body is a laundry list of classes renamed, deleted, and rewired. Nobody outside the codebase knows what a `SharingLauncher` or `GetTimelineBannerUseCase` is. Compare:

```
✅ GOOD — Describes the same change as an experience:
feat(timeline): show contextual suggestion cards on timeline

The timeline now shows contextual suggestions that respond to what
you've been doing. Unfinished drafts surface a card that takes you
straight back to the editor. Past memories from the same date appear
as shareable "on this day" cards. When there's nothing captured yet,
a gentle prompt encourages you to start writing, showing your nearby
location when available.
```

More examples of passable-looking tech dumps:

```
❌ SNEAKY — Uses "support" but describes architecture:
feat(editor): support multi-format note capture

Add AudioCaptureManager and VideoCaptureManager implementing the
CaptureStrategy interface. Each format registers through the
EditorModule DI container and is resolved at runtime based on the
selected capture mode.

✅ GOOD:
feat(editor): capture audio and video notes

You can now record audio memos and video clips directly in the
editor alongside text and photos. Each format appears as a
playable card in your timeline.
```

```
❌ SNEAKY — Starts user-facing, then derails:
feat(sharing): share memories to social platforms

Implement ShareIntentBuilder with platform-specific strategies for
Instagram Stories and the system share sheet. Uses ContentProvider
for URI resolution and FileProvider for secure media access.

✅ GOOD:
feat(sharing): share memories to social platforms

You can now share a memory to Instagram Stories or any app on your
phone via the system share sheet. Photos and videos are included
automatically.
```

**The test**: If you removed every class name, interface name, and module name from the body, would a sentence still say something meaningful? If not, rewrite it.

#### Reframed Technical Detail

A subtler variant: the message avoids class names but still describes implementation mechanics dressed up in user-facing language. The sentence is _about_ the code, not about the experience.

```
❌ REFRAMED — Technical detail wearing a user costume:
feat(settings): add Memories settings screen

All settings screens now use the expandable large title header that
collapses as you scroll. Shared toggle and section composables have
been extracted to the ui module so future settings screens don't
need to redeclare them.
```

No class names appear, but "expandable large title header that collapses as you scroll" describes a UI component choice, and "shared toggle and section composables extracted to the ui module" is pure refactoring detail. A user would never say either sentence.

```
✅ GOOD — Same change, user perspective only:
feat(settings): add Memories settings to control recommendations and recall

You can now toggle recommendations on or off right from the settings
overview, or tap through to fine-tune which types of recall the app
uses — like turning off smart recall while keeping other suggestions
active.
```

**The rule for `feat` and `fix` commits is absolute**: the body must contain _zero_ implementation detail — no component names, no architectural decisions, no refactoring rationale. If the change also includes refactoring or structural cleanup, those details belong in a separate `refactor` or `chore` commit, not buried in a `feat` body. Only `refactor`, `chore`, `perf`, and `docs` commits may reference internal structure.

### For Bug Fixes

Answer: What was broken? What caused it? How does the fix address it?

```
fix(auth): correct token refresh race condition

Under high load, concurrent token refresh requests could cause
multiple tokens to be issued for the same session. Root cause was
missing distributed lock around the refresh operation.

Now we use a Redis-based lock to serialize token refresh, ensuring
only one refresh happens per session at a time.
```

### For Refactors

Answer: Why was restructuring necessary? What benefit does it provide?

```
refactor(core): extract validation logic to shared service

Validation rules were duplicated across three form components,
making it impossible to update rules consistently. By extracting
to a shared ValidationService, we ensure:

1. Single source of truth for validation rules
2. Easy to add new validators without touching components
3. Testable in isolation from UI concerns
4. Enables future rule versioning and A/B testing

This also unblocks the API documentation effort waiting on
consistent field validation specs.
```

---

## Special Annotations

### Breaking Changes

Mark explicitly with `!` and `BREAKING CHANGE:` footer:

```
feat(api)!: change authentication response format

BREAKING CHANGE: The /auth/login endpoint now returns tokens in a
nested object instead of at the root level.

Before: { "accessToken": "..." }
After: { "tokens": { "accessToken": "..." } }

This aligns with OAuth2 RFC 6749 standard format and enables future
extension with refresh tokens and token metadata.

Migration: Update all API clients to access tokens.accessToken
instead of accessToken.
```

### Issue References

Connect to project management:

- `Fixes #123` or `Closes #456` — Closes issue when merged
- `Related to #789` — References without closing
- List multiple on separate lines if relevant

```
fix(auth): handle expired session gracefully

When a user's session expires mid-request, we now properly clear
expired tokens and redirect to login instead of showing a generic
error.

Fixes #234
Related to #567
```

### Co-Authors

Credit collaborators:

```
feat(api): implement rate limiting middleware

Co-Authored-By: Alice Smith <alice@example.com>
Co-Authored-By: Bob Jones <bob@example.com>
```

---

## Forbidden Patterns

🚨 **NEVER include project phases in commit messages.**

Phase numbers (Phase 1, Phase 5, Phase 6.1) are internal project management that means nothing to someone reading git history. They pollute the permanent record with meaningless metadata.

```
❌ feat(cli-rs): implement session management (Phase 6.1)
```

**Correct approach**: Explain what the feature IS and why it exists.

```
✅ feat(cli-rs): implement session management commands

Add comprehensive session management with interactive picker and
multiple output formats. This enables users to switch between
conversations without losing context, addressing the workflow
interruption issues reported in support tickets.
```

---

## Brevity for Trivial Changes

The guidance about capturing context applies to meaningful changes. For genuinely small, straightforward changes, brevity is appropriate.

**When minimal commit messages are OK:**

- Link path updates (documentation references, import paths)
- Simple configuration value changes
- Adding a single file with obvious purpose
- Fixing typos in comments or documentation
- Removing unused imports or dead code
- Formatting/style fixes that don't change behavior
- Adding a missing dependency with no code changes

```
✅ docs: fix typo in README

✅ chore: bump node version to 20.x

✅ refactor: remove unused UserService class

✅ chore: update documentation links in claude command skills
```

**The Distinction:**

- **Meaningful changes** (new features, architectural decisions, bug fixes, refactors with reasoning): Include context, explain reasoning, document full scope
- **Trivial changes** (obvious link updates, simple additions, clear fixes): Brief title is sufficient, the diff explains everything

**When in doubt, include context.** Err toward explanation when the change could have multiple interpretations or when the "why" isn't immediately obvious from the diff.

---

## Covering Multiple Items in a Single Task

**CRITICAL: When a user asks Claude to do multiple items (1, 2, 3, 4), the commit message must address ALL of them, not just the last one.**

This is a common failure mode: Claude implements all four items correctly, but then writes a commit message that only describes item 4 because that was the most recent work.

**Pattern**: User says "Please: 1) Add validation to UserForm, 2) Create validation utility, 3) Add tests, 4) Update types"

Claude must:

1. Parse all four items explicitly
2. Implement all four (which will be correct)
3. **Write a commit message that references all four items** and explains how they work together

```
❌ INCORRECT — Only documents the last item:
feat(auth): add UserForm validation types

Update TypeScript types to support validation metadata on form fields.
```

This completely loses items 1, 2, and 3 from the record.

```
✅ CORRECT — Documents all items with complete context:
feat(auth): implement form validation system for UserForm

Centralized form validation using new ValidationRule and ValidationError
types to address inconsistency where different pages handled validation
differently—some had comprehensive checks via custom validators, others
had none.

By extracting validators (validateEmail, validatePassword, etc.) into
reusable utilities in @lovelace-ai/utils, we eliminate logic duplication
in every form and ensure consistent error messages across the application.
The FormState type manages validation state independently from UI
concerns, making both easier to test and change separately.

We chose a utility-based approach over component props validation
because it allows applying the same validators across multiple forms and
updating global validation rules in one place (e.g., password strength
requirements).

Addresses #789 where form validation wasn't consistent between pages.
```

This explains:

- The problem (inconsistent validation across pages)
- Specific technical details (ValidationRule, ValidationError, FormState types; validateEmail, validatePassword functions)
- The reasoning (reusability, consistency, separation of concerns, centralized updates)
- Why this approach was chosen (vs alternatives like component props)
- What business need it addresses

Balance is key: include specific names and symbols (ValidationError, validateEmail) when they're important to understanding the architecture, but focus on **why** these decisions were made and **what problem they solve**, not just listing that they were created.

---

## Related Documentation

- **[Git Guidelines](./git-guidelines.md)** — Staging safety, atomic workflow, pre-commit hooks
- **[Commit Scopes Reference](./commit-scopes.md)** — Authoritative lookup table for scope names
- **[Project Conventions](../../../CLAUDE.md)** — Full project conventions and standards

---

**Remember:** Commit messages are documentation that persists longer than the code. Write them for someone reading git history years from now with no other context.

</details>
