# Commit message standards

Every commit that lands on `main` becomes a line in a changelog, so a commit message is written
for the person who will read that changelog and, later, for whoever runs `git blame` on the code.
The diff already shows what changed line by line. The message says what the change means.

A message has a title, an optional body, and optional trailers:

```text
type(scope): Description of the change

A paragraph or two in full sentences.

Co-authored-by: Name <name@example.com>
```

The title is one line. A blank line separates it from the body, and a blank line separates the
body from the trailers. The hooks reject a message with no blank line after the title.

## Choosing the type

There are exactly three types: `feat`, `fix` and `chore`. Ask one question: would a person using
the product notice the difference?

- `feat` is for a change that lets a person do something they could not do before.
- `fix` is for a change that makes something work that was broken or wrong.
- `chore` is for everything else: refactoring, tests, documentation, dependency updates, build
  and tooling work, and performance work nobody would see.

Refactoring is not a type of its own here, even though Conventional Commits has one. A refactor
that changes nothing a person can see is a `chore`. If one change contains a refactor and a new
behavior, make two commits, with the refactor first, so each can be understood and reverted alone.

Keep a change together with its tests and the documentation it needs. Several small, complete
commits are fine, but do not split a change into "code", "tests" and "docs" commits.

## Choosing the scope

The scope is the part of the product the change belongs to, written in parentheses after the
type. It is required, and it must be one of the scopes registered in
[`.hypertext/commits.json`](../../../.hypertext/commits.json). [Commit scopes](commit-scopes.md)
lists every scope with what it covers and what belongs elsewhere.

Choose the scope by what the change does for a person, not by where the files are. A change to the
backup progress indicator is `sync` even if most of the edited files are in a settings screen.
Build, hook and tooling work is `dx`. Names such as `core`, `ui`, `docs`, `build` and `tests` are
never valid, because they describe where code lives rather than what it is for.

## Writing the title

The title has the form `type(scope): Description`. The type and scope are lowercase, and the
description starts with a capital letter and does not end with a period. Write it in the
imperative mood, as in `Show`, `Resume` or `Bound`, so that it reads as "this commit will ...".
Keep it to about 72 characters so it fits in `git log --oneline`.

Say what actually changed. A description such as `Update code` or `WIP` is rejected, and so are a few
similar generic placeholders, but the hook cannot judge a vague title that is merely unhelpful. Capitalize proper nouns such as Android, Kotlin,
Gradle and Compose. Do not put project phase numbers or ticket labels in the title; they mean
nothing to someone reading the history later.

To mark a breaking change, add `!` after the scope and add a paragraph that starts with
`BREAKING CHANGE:` explaining what breaks and how to migrate.

## Writing the body of a feat or fix

A `feat` or `fix` is read by people who use the app and have never seen the code. The title and
body describe the change from their side: what they can now do, or what now works. The body has no
implementation detail, which means no class or module names, no architecture, no library names
and no refactoring rationale. If you took out every technical word, the message should still say
something meaningful.

This body describes the mechanism, so the hooks reject it. They reject it because it names a
`ViewModel` and quotes code in backticks:

```text
feat(editor): Add voice recording to the editor

Adds a `VoiceRecorder` composable backed by a ViewModel that is
provided through Koin.
```

The same change, written for the person using it, is accepted:

```text
feat(editor): Record voice notes while you write

You can now record a voice note without leaving the entry you are
writing. The recording appears in the entry as a clip you can play
back, and you can keep typing while it records.
```

Technical detail can also be disguised as product language. The hooks only look for certain words,
so a message like the next one passes them, but a reviewer will ask for it to be rewritten. It
describes how the screen is built, which nobody using the app would say:

```text
feat(settings): Add the Memories settings screen

The screen uses the shared expandable header and the toggle rows were
moved into the common settings module.
```

The rewrite says what a person can do:

```text
feat(settings): Choose which memories the app suggests

You can now turn memory suggestions on or off from Settings, or open
them to choose which kinds of memories the app brings back, such as
keeping "On this day" while turning off other suggestions.
```

A `fix` body says what went wrong for the person and what works now. The cause belongs in the
body only when it helps a reader understand the symptom, and then it is described in plain terms:

```text
fix(sync): Resume interrupted backups instead of starting over

A backup that was interrupted by a lost connection used to begin
again from the first photo. It now continues where it stopped, so a
journal with many photos finishes backing up on a weak connection.
```

Features and fixes in `activitypub`, `atproto` and `dx` are the exception. Those scopes are
registered as technical, because their users are developers, so their bodies may name protocols and
libraries.

## Writing the body of a chore

A `chore` has to have a body. The body explains what was done and why it matters, and it is the
place for implementation detail, because the reader is a maintainer. Describe the situation before
the change, what is different now, and whether any behavior changed:

```text
chore(timeline): Load notes one page at a time when building a day

The day view read every note for the day and then picked out the page
it needed. It now asks the database for only the requested page, which
keeps memory use flat for days with hundreds of notes. Behavior is
unchanged, and a test covers a day with more than one page of notes.
```

Documentation and tooling work follows the same pattern and takes the scope of the area it
supports, or `dx` for the repository's own tooling:

```text
chore(dx): Explain how to choose a commit scope

The commit standards now say that a scope names the product area a
change serves, with the scope table linked from the first paragraph,
so new contributors do not pick a scope from the folder they edited.
```

Wrap body lines at 72 characters. The commit hook rewraps long lines for you, but a message you
write wrapped is easier to review.

## Trailers

Trailers go in the last paragraph of the message, one `Key: value` per line. A trailer is not
part of the prose, so it is exempt from the body rules above.

When an AI assistant helped write the change, credit it with a `Co-authored-by` trailer that has
the assistant's name and the address its tool publishes, in the form `Name <address>`:

```text
Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
```

The hook checks that every `Co-authored-by` value has this form. When the environment variable
`HYPERTEXT_AGENT_COAUTHOR` is set, as the agent tooling does, the commit must also contain that
exact identity as a trailer, and the hook names the missing one if it is absent. Do not credit a
person who did not contribute, and do not invent an identity.

Reference an issue with `Refs: #123`, `Fixes: #123` or `Closes: #123`, or a link in the same
position. Put references last.

## What is checked

Two hooks check messages, and neither depends on remembering these rules.

The `commit-msg` hook runs when you commit. It first rewraps body prose to 72 columns, then
rejects the message if any of these is true:

- The title is not `feat`, `fix` or `chore`, followed by a lowercase scope in parentheses, a
  colon, a space and a description.
- The scope is not registered in `.hypertext/commits.json`.
- The description is a generic placeholder such as `Update code` or `WIP`.
- The second line is not blank.
- A `feat` or `fix` outside the technical scopes mentions words such as `refactor`, `middleware`,
  `OAuth`, `SQL` or `ViewModel`, quotes code in backticks, or names a source file.
- A `chore` has no body, or only trailers.
- The title has `!` but no `BREAKING CHANGE:` paragraph.
- A `Co-authored-by` value is malformed, or the identity in `HYPERTEXT_AGENT_COAUTHOR` is missing.

The `pre-push` hook applies the same checks to every commit you are about to publish that is not
already on `main`, and it refuses merge commits. Fix a rejected message by amending the commit or,
for an older commit on your branch, by rewording it during a rebase onto `origin/main`.

The checks cannot tell whether a body is good. They do not look at the capital letter, the title
length, the mood of the verb, or whether a `feat` body hides implementation detail in ordinary
words, so those are matters for you and for review.

See [Git guidelines](git-guidelines.md) for how to stage, commit and land a branch.
